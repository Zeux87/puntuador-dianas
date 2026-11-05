/*
  Lector de impactos para Puntuador de dianas (Línea de Tiro)

  Cámara ESP32 que se coloca junto a la diana. Crea su propia red WiFi, vigila la diana y,
  cuando aparece un agujero nuevo y la imagen se queda quieta, guarda esa foto y avisa.
  La app pregunta cada poco por /estado, descarga la foto del aviso y localiza el impacto
  comparándola con la anterior.

  Placas:
    - Seeed XIAO ESP32S3 Sense (recomendada). En Arduino IDE: placa «XIAO_ESP32S3», PSRAM «OPI PSRAM».
    - ESP32-CAM AI-Thinker. Placa «AI Thinker ESP32-CAM».

  Red WiFi: LineaDeTiro-Lector  ·  clave: lineadetiro  ·  dirección: http://192.168.4.1

  Rutas:
    /            página para apuntar la cámara y ajustar
    /estado      JSON con el último aviso (seq, tipo, posición aproximada…)
    /foto        JPEG actual; /foto?seq=N da la foto exacta del aviso N si es el último
    /referencia  toma la imagen actual como diana nueva (aviso de tipo «cambio»)
    /ajustes     ?sens=0..100  ?giro=0|1  ?luz=0|1   (se guardan en la placa)
*/

#include <Arduino.h>
#include <WiFi.h>
#include <Preferences.h>
#include "esp_camera.h"
#include "esp_http_server.h"
#include "esp_wifi.h"
#include "img_converters.h"

#define VERSION_LECTOR "1.1"

// ===================== Red =====================
static const char *RED_NOMBRE = "LineaDeTiro-Lector";
static const char *RED_CLAVE  = "lineadetiro";       // mínimo 8 caracteres
static const int   RED_CANAL  = 6;

// ===================== Pines de la cámara =====================
#if defined(ARDUINO_XIAO_ESP32S3)
  #define PLACA_NOMBRE "XIAO ESP32S3 Sense"
  #define PWDN_GPIO_NUM  -1
  #define RESET_GPIO_NUM -1
  #define XCLK_GPIO_NUM  10
  #define SIOD_GPIO_NUM  40
  #define SIOC_GPIO_NUM  39
  #define Y9_GPIO_NUM    48
  #define Y8_GPIO_NUM    11
  #define Y7_GPIO_NUM    12
  #define Y6_GPIO_NUM    14
  #define Y5_GPIO_NUM    16
  #define Y4_GPIO_NUM    18
  #define Y3_GPIO_NUM    17
  #define Y2_GPIO_NUM    15
  #define VSYNC_GPIO_NUM 38
  #define HREF_GPIO_NUM  47
  #define PCLK_GPIO_NUM  13
  #define LED_ESTADO     21     // LED naranja de la placa (se enciende con LOW)
  #define LED_ACTIVO_BAJO 1
  #define LUZ_PIN        -1     // pon aquí un pin (p. ej. 1 = D0) si añades un MOSFET con tira LED
#elif defined(CONFIG_IDF_TARGET_ESP32)
  #define PLACA_NOMBRE "ESP32-CAM AI-Thinker"
  #define PWDN_GPIO_NUM  32
  #define RESET_GPIO_NUM -1
  #define XCLK_GPIO_NUM   0
  #define SIOD_GPIO_NUM  26
  #define SIOC_GPIO_NUM  27
  #define Y9_GPIO_NUM    35
  #define Y8_GPIO_NUM    34
  #define Y7_GPIO_NUM    39
  #define Y6_GPIO_NUM    36
  #define Y5_GPIO_NUM    21
  #define Y4_GPIO_NUM    19
  #define Y3_GPIO_NUM    18
  #define Y2_GPIO_NUM     5
  #define VSYNC_GPIO_NUM 25
  #define HREF_GPIO_NUM  23
  #define PCLK_GPIO_NUM  22
  #define LED_ESTADO     33     // LED rojo pequeño de la parte de atrás (se enciende con LOW)
  #define LED_ACTIVO_BAJO 1
  #define LUZ_PIN         4     // flash blanco de la placa
#else
  #error "Placa no soportada: elige XIAO_ESP32S3 o AI Thinker ESP32-CAM"
#endif

// ===================== Estado compartido =====================
static Preferences prefs;
static SemaphoreHandle_t mux;

static int ajSens = 50;          // 0 = solo cambios muy claros, 100 = muy sensible
static int ajGiro = 0;           // 1 = imagen girada 180° (cámara montada al revés)
static int ajLuz  = 0;           // 1 = luz encendida (si hay LUZ_PIN)

static int W = 0, H = 0;         // tamaño de la foto
static int dw = 0, dh = 0;       // tamaño de la imagen de vigilancia (1/8)
static const char *sensorNombre = "?";

static const size_t CAP_JPG = 1024 * 1024;
static uint8_t *ultimo = nullptr;  static size_t ultimoLen = 0;   // última foto
static uint8_t *evFoto = nullptr;  static size_t evLen = 0;       // foto del último aviso

static uint8_t *rgb = nullptr;   // RGB565 a 1/8
static uint8_t *cur = nullptr, *ref = nullptr, *prev = nullptr;
static int orden565 = -1;        // orden de bytes del RGB565 que devuelve el decodificador

struct Aviso { uint32_t seq; char tipo[10]; float x, y; int pix; uint32_t ms; bool manual; };
static Aviso ev = { 0, "nada", 0, 0, 0, 0, false };

static volatile bool pedirRef = false;
static bool refOk = false;
static int calentando = 0;
enum { REPOSO, CAMBIANDO };
static int estadoVig = REPOSO;
static int quietos = 0;
static uint32_t tCambio = 0;
static uint32_t ledHasta = 0;

// ===================== Utilidades =====================
static void led(bool on) {
  if (LED_ESTADO < 0) return;
  digitalWrite(LED_ESTADO, (on ^ LED_ACTIVO_BAJO) ? HIGH : LOW);
}
static void aplicarLuz() {
  if (LUZ_PIN < 0) return;
  pinMode(LUZ_PIN, OUTPUT);
  digitalWrite(LUZ_PIN, ajLuz ? HIGH : LOW);
}
static int umbral()   { return 40 - ajSens / 4; }              // diferencia de gris para contar un píxel
static int minPix()   { return 3 + (100 - ajSens) / 20; }      // píxeles cambiados para empezar a mirar
static int maxImpacto(){ return dw * dh / 80; }                // más que esto ya no es un perdigón

static void aplicarGiro() {
  sensor_t *s = esp_camera_sensor_get();
  if (!s) return;
  int vf = 0, hm = 0;
  if (s->id.PID == OV3660_PID) vf = 1;   // el OV3660 de la XIAO sale invertido de fábrica
  if (ajGiro) { vf ^= 1; hm ^= 1; }      // girar 180° = voltear y reflejar a la vez (sin espejo)
  s->set_vflip(s, vf);
  s->set_hmirror(s, hm);
}

// ===================== Cámara =====================
static bool iniciarCamara() {
  camera_config_t c = {};
  c.ledc_channel = LEDC_CHANNEL_0;
  c.ledc_timer = LEDC_TIMER_0;
  c.pin_d0 = Y2_GPIO_NUM;  c.pin_d1 = Y3_GPIO_NUM;  c.pin_d2 = Y4_GPIO_NUM;  c.pin_d3 = Y5_GPIO_NUM;
  c.pin_d4 = Y6_GPIO_NUM;  c.pin_d5 = Y7_GPIO_NUM;  c.pin_d6 = Y8_GPIO_NUM;  c.pin_d7 = Y9_GPIO_NUM;
  c.pin_xclk = XCLK_GPIO_NUM; c.pin_pclk = PCLK_GPIO_NUM;
  c.pin_vsync = VSYNC_GPIO_NUM; c.pin_href = HREF_GPIO_NUM;
  c.pin_sccb_sda = SIOD_GPIO_NUM; c.pin_sccb_scl = SIOC_GPIO_NUM;
  c.pin_pwdn = PWDN_GPIO_NUM; c.pin_reset = RESET_GPIO_NUM;
  c.xclk_freq_hz = 20000000;
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size = FRAMESIZE_UXGA;
  c.jpeg_quality = 10;
  c.fb_count = 2;
  c.fb_location = CAMERA_FB_IN_PSRAM;
  c.grab_mode = CAMERA_GRAB_LATEST;
  if (esp_camera_init(&c) != ESP_OK) return false;

  sensor_t *s = esp_camera_sensor_get();
  framesize_t fs = FRAMESIZE_UXGA;
  W = 1600; H = 1200; sensorNombre = "OV2640";
  if (s->id.PID == OV3660_PID)      { fs = FRAMESIZE_QXGA; W = 2048; H = 1536; sensorNombre = "OV3660"; }
  else if (s->id.PID == OV5640_PID) { fs = FRAMESIZE_QXGA; W = 2048; H = 1536; sensorNombre = "OV5640"; }
  s->set_framesize(s, fs);
  s->set_quality(s, 10);
  s->set_lenc(s, 1);
  s->set_dcw(s, 1);
  if (s->id.PID == OV3660_PID) { s->set_brightness(s, 1); s->set_saturation(s, -2); }
  aplicarGiro();
  dw = W / 8; dh = H / 8;
  return true;
}

static inline uint8_t gris565(uint16_t v) {
  int r = (v >> 11) & 31, g = (v >> 5) & 63, b = v & 31;
  return (uint8_t)((r * 8 * 77 + g * 4 * 150 + b * 8 * 29) >> 8);
}
// El decodificador ha cambiado de orden de bytes entre versiones: se elige el que da la imagen más suave
static void aGris() {
  if (orden565 < 0) {
    uint32_t sa = 0, sb = 0;
    for (int y = 0; y < dh; y += 2) for (int x = 1; x < dw; x++) {
      int i = (y * dw + x) * 2;
      uint16_t a0 = rgb[i] | (rgb[i + 1] << 8), a1 = rgb[i - 2] | (rgb[i - 1] << 8);
      uint16_t b0 = (rgb[i] << 8) | rgb[i + 1], b1 = (rgb[i - 2] << 8) | rgb[i - 1];
      sa += abs(gris565(a0) - gris565(a1));
      sb += abs(gris565(b0) - gris565(b1));
    }
    orden565 = sa <= sb ? 0 : 1;
  }
  int n = dw * dh;
  if (orden565 == 0) for (int i = 0; i < n; i++) cur[i] = gris565(rgb[2 * i] | (rgb[2 * i + 1] << 8));
  else               for (int i = 0; i < n; i++) cur[i] = gris565((rgb[2 * i] << 8) | rgb[2 * i + 1]);
}

// ===================== Vigilancia =====================
static void emitir(const char *tipo, float x, float y, int pix, bool manual) {
  xSemaphoreTake(mux, portMAX_DELAY);
  if (ultimoLen && ultimoLen <= CAP_JPG) { memcpy(evFoto, ultimo, ultimoLen); evLen = ultimoLen; }
  ev.seq++;
  strncpy(ev.tipo, tipo, sizeof(ev.tipo) - 1); ev.tipo[sizeof(ev.tipo) - 1] = 0;
  ev.x = x; ev.y = y; ev.pix = pix; ev.ms = millis(); ev.manual = manual;
  xSemaphoreGive(mux);
  ledHasta = millis() + 400;
  Serial.printf("Aviso %u: %s (%d px) en %.3f, %.3f\n", (unsigned)ev.seq, tipo, pix, x, y);
}

static void vigilar() {
  const int n = dw * dh;
  if (!refOk) {
    memcpy(prev, cur, n);
    if (++calentando >= 8) { memcpy(ref, cur, n); refOk = true; emitir("inicio", 0, 0, 0, false); }
    return;
  }
  if (pedirRef) {
    pedirRef = false;
    memcpy(ref, cur, n); memcpy(prev, cur, n);
    estadoVig = REPOSO;
    emitir("cambio", 0, 0, 0, true);
    return;
  }
  // Corrección de brillo global (exposición automática)
  int32_t sR = 0, sP = 0;
  for (int i = 0; i < n; i++) { sR += cur[i] - ref[i]; sP += cur[i] - prev[i]; }
  const int offR = sR / n, offP = sP / n, T = umbral();
  int cambiados = 0, mov = 0; int64_t sx = 0, sy = 0;
  for (int y = 2; y < dh - 2; y++) {
    const int fila = y * dw;
    for (int x = 2; x < dw - 2; x++) {
      const int i = fila + x;
      if (abs(cur[i] - ref[i] - offR) > T) { cambiados++; sx += x; sy += y; }
      if (abs(cur[i] - prev[i] - offP) > T) mov++;
    }
  }
  const int mp = minPix();
  if (estadoVig == REPOSO) {
    if (cambiados >= mp) { estadoVig = CAMBIANDO; quietos = 0; tCambio = millis(); }
    else for (int i = 0; i < n; i++) ref[i] = (uint8_t)((ref[i] * 15 + cur[i] + 8) >> 4);   // sigue la luz poco a poco
  } else {
    quietos = mov < mp ? quietos + 1 : 0;
    if (quietos >= 2) {
      if (cambiados < mp) { /* falsa alarma: era un reflejo o algo que ya se fue */ }
      else {
        const float fx = (float)sx / cambiados / dw, fy = (float)sy / cambiados / dh;
        emitir(cambiados <= maxImpacto() ? "impacto" : "cambio", fx, fy, cambiados, false);
      }
      memcpy(ref, cur, n);
      estadoVig = REPOSO;
    }
  }
  memcpy(prev, cur, n);
}

// ===================== Servidor web =====================
static void cabeceras(httpd_req_t *r) {
  httpd_resp_set_hdr(r, "Access-Control-Allow-Origin", "*");
  httpd_resp_set_hdr(r, "Cache-Control", "no-store");
}
static int leerParam(httpd_req_t *r, const char *clave, int defecto) {
  char q[96], v[16];
  if (httpd_req_get_url_query_str(r, q, sizeof(q)) != ESP_OK) return defecto;
  if (httpd_query_key_value(q, clave, v, sizeof(v)) != ESP_OK) return defecto;
  return atoi(v);
}
static bool tieneParam(httpd_req_t *r, const char *clave) {
  char q[96], v[16];
  if (httpd_req_get_url_query_str(r, q, sizeof(q)) != ESP_OK) return false;
  return httpd_query_key_value(q, clave, v, sizeof(v)) == ESP_OK;
}

static esp_err_t hEstado(httpd_req_t *r) {
  char j[400];
  xSemaphoreTake(mux, portMAX_DELAY);
  Aviso e = ev;
  xSemaphoreGive(mux);
  snprintf(j, sizeof(j),
    "{\"v\":\"%s\",\"placa\":\"%s\",\"sensor\":\"%s\",\"ancho\":%d,\"alto\":%d,\"seq\":%u,\"tipo\":\"%s\","
    "\"x\":%.4f,\"y\":%.4f,\"pix\":%d,\"manual\":%s,\"hace\":%u,\"sens\":%d,\"giro\":%d,\"luz\":%d,\"hayLuz\":%s,"
    "\"vigilando\":%s,\"uptime\":%u}",
    VERSION_LECTOR, PLACA_NOMBRE, sensorNombre, W, H, (unsigned)e.seq, e.tipo, e.x, e.y, e.pix, e.manual ? "true" : "false",
    (unsigned)(millis() - e.ms), ajSens, ajGiro, ajLuz, LUZ_PIN >= 0 ? "true" : "false",
    refOk ? "true" : "false", (unsigned)(millis() / 1000));
  cabeceras(r);
  httpd_resp_set_type(r, "application/json");
  return httpd_resp_sendstr(r, j);
}

static esp_err_t hFoto(httpd_req_t *r) {
  const int pide = leerParam(r, "seq", -1);
  xSemaphoreTake(mux, portMAX_DELAY);
  const bool delAviso = pide >= 0 && (uint32_t)pide == ev.seq && evLen > 0;
  const uint8_t *src = delAviso ? evFoto : ultimo;
  const size_t len = delAviso ? evLen : ultimoLen;
  const uint32_t seq = ev.seq;
  uint8_t *copia = len ? (uint8_t *)ps_malloc(len) : nullptr;
  if (copia) memcpy(copia, src, len);
  xSemaphoreGive(mux);
  cabeceras(r);
  if (!copia) { httpd_resp_set_status(r, "503 Service Unavailable"); return httpd_resp_sendstr(r, "sin foto"); }
  char s[16]; snprintf(s, sizeof(s), "%u", (unsigned)(delAviso ? seq : 0));
  httpd_resp_set_hdr(r, "X-Seq", s);
  httpd_resp_set_hdr(r, "Access-Control-Expose-Headers", "X-Seq");
  httpd_resp_set_type(r, "image/jpeg");
  esp_err_t res = httpd_resp_send(r, (const char *)copia, len);
  free(copia);
  return res;
}

static esp_err_t hReferencia(httpd_req_t *r) {
  pedirRef = true;
  cabeceras(r);
  httpd_resp_set_type(r, "application/json");
  return httpd_resp_sendstr(r, "{\"ok\":true}");
}

static esp_err_t hAjustes(httpd_req_t *r) {
  if (tieneParam(r, "sens")) { ajSens = constrain(leerParam(r, "sens", 50), 0, 100); prefs.putInt("sens", ajSens); }
  if (tieneParam(r, "giro")) { ajGiro = leerParam(r, "giro", 0) ? 1 : 0; prefs.putInt("giro", ajGiro); aplicarGiro(); pedirRef = true; }
  if (tieneParam(r, "luz"))  { ajLuz = leerParam(r, "luz", 0) ? 1 : 0; prefs.putInt("luz", ajLuz); aplicarLuz(); }
  return hEstado(r);
}

static const char PAGINA[] PROGMEM = R"HTML(<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Lector de impactos</title>
<style>body{font-family:system-ui,sans-serif;margin:0;background:#0e1213;color:#e8ebe6}main{padding:12px;max-width:900px;margin:auto}
#vista{position:relative;border-radius:8px;overflow:auto;background:#222;max-height:70vh}#f{display:block;width:100%}
#vista.lupa #f{width:auto;max-width:none}
button{font:inherit;padding:8px 14px;border-radius:8px;border:1px solid #16cde1;background:#162022;color:#e8ebe6;margin:4px 4px 4px 0}
button.on{background:#16cde1;color:#0e1213}p{margin:6px 0}small{opacity:.7}
#enf{border:1px solid #2b3a3c;border-radius:8px;padding:8px 10px;margin:8px 0}#barra{height:14px;background:#222;border-radius:7px;overflow:hidden}
#barra i{display:block;height:100%;width:0;background:#16cde1;transition:width .2s}#barra i.max{background:#35c76a}
#n{font-size:1.6em;font-weight:700}</style></head><body><main>
<h2>Lector de impactos</h2>
<p id="e">Conectando…</p>
<div id="vista"><img id="f" alt="Vista de la cámara"></div>
<div id="enf"><p>Nitidez del centro: <span id="n">–</span> <small>(mejor: <span id="mx">–</span> · <span id="fps">–</span> fotos/s en esta vista)</small></p>
<div id="barra"><i id="bi"></i></div>
<p><small>Para enfocar: diana en su sitio y bien iluminada, gira la lente muy poco (1/16 de vuelta), espera 2 fotos y quédate donde el número sea más alto. Si baja, gira hacia el otro lado.</small></p></div>
<p><button id="bl" onclick="lupa()">Lupa 1:1</button><button onclick="mx=0">Reiniciar mejor</button>
<button onclick="go('/referencia')">Diana nueva (referencia)</button>
<button onclick="go('/ajustes?giro='+(1-(S.giro||0)))">Girar 180°</button>
<button id="luz" onclick="go('/ajustes?luz='+(1-(S.luz||0)))" hidden>Luz</button></p>
<p>Sensibilidad <input id="s" type="range" min="0" max="100" onchange="go('/ajustes?sens='+this.value)"> <b id="so"></b></p>
<p><small>Esto no es vídeo: la vista trae fotos completas una tras otra. El lector vigila por su cuenta unas 5 veces por segundo y avisa a la app en menos de un segundo tras cada impacto.</small></p>
</main><script>
let S={},mx=0,t0=Date.now(),nf=0,cv=document.createElement('canvas');const $=i=>document.getElementById(i);
function go(u){fetch(u).then(r=>r.json()).then(j=>{S=j;pinta()}).catch(()=>{})}
function pinta(){$('e').textContent=`${S.placa} · ${S.sensor} ${S.ancho}×${S.alto} · aviso ${S.seq} (${S.tipo}) · sensibilidad ${S.sens}`;
$('s').value=S.sens;$('so').textContent=S.sens;$('luz').hidden=!S.hayLuz}
function lupa(){const v=$('vista');v.classList.toggle('lupa');$('bl').classList.toggle('on');centra()}
function centra(){const v=$('vista'),f=$('f');if(v.classList.contains('lupa')){v.scrollLeft=(f.naturalWidth-v.clientWidth)/2;v.scrollTop=(f.naturalHeight-v.clientHeight)/2}}
function nitidez(im){const w=Math.min(400,im.naturalWidth),h=Math.min(400,im.naturalHeight);cv.width=w;cv.height=h;
const x=cv.getContext('2d',{willReadFrequently:true});x.drawImage(im,(im.naturalWidth-w)/2,(im.naturalHeight-h)/2,w,h,0,0,w,h);
const d=x.getImageData(0,0,w,h).data,g=new Float32Array(w*h);for(let i=0,j=0;i<g.length;i++,j+=4)g[i]=(d[j]+2*d[j+1]+d[j+2])/4;
let s=0,n=0;for(let y=1;y<h-1;y++)for(let x2=1;x2<w-1;x2++){const i=y*w+x2;
const gx=g[i-w+1]+2*g[i+1]+g[i+w+1]-g[i-w-1]-2*g[i-1]-g[i+w-1],gy=g[i+w-1]+2*g[i+w]+g[i+w+1]-g[i-w-1]-2*g[i-w]-g[i-w+1];
const m=gx*gx+gy*gy;if(m>400){s+=m;n++}}return n?Math.round(Math.sqrt(s/(w*h))):0}
function foto(){const i=new Image();i.onload=()=>{const pr=!$('f').naturalWidth;$('f').src=i.src;if(pr)setTimeout(centra,50);
const v=nitidez(i);mx=Math.max(mx,v);$('n').textContent=v;$('mx').textContent=mx;
const b=$('bi');b.style.width=(mx?100*v/mx:0)+'%';b.classList.toggle('max',v>=mx*0.97);
nf++;const dt=(Date.now()-t0)/1000;if(dt>3){$('fps').textContent=(nf/dt).toFixed(1);nf=0;t0=Date.now()}
setTimeout(foto,50)};i.onerror=()=>setTimeout(foto,1500);i.src='/foto?t='+Date.now()}
setInterval(()=>go('/estado'),2000);go('/estado');foto();
</script></body></html>)HTML";

static esp_err_t hInicio(httpd_req_t *r) {
  httpd_resp_set_type(r, "text/html; charset=utf-8");
  return httpd_resp_send(r, PAGINA, HTTPD_RESP_USE_STRLEN);
}

static void iniciarServidor() {
  httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
  cfg.stack_size = 8192;
  cfg.max_uri_handlers = 8;
  cfg.lru_purge_enable = true;
  cfg.max_open_sockets = 5;
  httpd_handle_t srv = nullptr;
  if (httpd_start(&srv, &cfg) != ESP_OK) { Serial.println("No arranca el servidor web"); return; }
  const httpd_uri_t rutas[] = {
    { "/",           HTTP_GET, hInicio,     nullptr },
    { "/estado",     HTTP_GET, hEstado,     nullptr },
    { "/foto",       HTTP_GET, hFoto,       nullptr },
    { "/referencia", HTTP_GET, hReferencia, nullptr },
    { "/ajustes",    HTTP_GET, hAjustes,    nullptr },
  };
  for (const auto &u : rutas) httpd_register_uri_handler(srv, &u);
}

// ===================== Arranque y bucle =====================
static void fallo(const char *txt) {
  Serial.println(txt);
  while (true) { led(true); delay(150); led(false); delay(150); }
}

void setup() {
  Serial.begin(115200);
  if (LED_ESTADO >= 0) pinMode(LED_ESTADO, OUTPUT);
  led(true);
  mux = xSemaphoreCreateMutex();

  prefs.begin("lector", false);
  ajSens = prefs.getInt("sens", 50);
  ajGiro = prefs.getInt("giro", 0);
  ajLuz  = prefs.getInt("luz", 0);
  aplicarLuz();

  if (!psramFound()) fallo("Falta PSRAM: activa «OPI PSRAM» en el menú de la placa");
  if (!iniciarCamara()) fallo("No encuentro la cámara: revisa el conector");

  ultimo = (uint8_t *)ps_malloc(CAP_JPG);
  evFoto = (uint8_t *)ps_malloc(CAP_JPG);
  rgb  = (uint8_t *)ps_malloc((size_t)dw * dh * 2 + 64);
  cur  = (uint8_t *)malloc((size_t)dw * dh);
  ref  = (uint8_t *)malloc((size_t)dw * dh);
  prev = (uint8_t *)malloc((size_t)dw * dh);
  if (!ultimo || !evFoto || !rgb || !cur || !ref || !prev) fallo("Sin memoria");

  WiFi.mode(WIFI_AP);
  WiFi.softAP(RED_NOMBRE, RED_CLAVE, RED_CANAL, 0, 3);
  WiFi.setTxPower(WIFI_POWER_19_5dBm);
  esp_wifi_set_ps(WIFI_PS_NONE);
  Serial.printf("Red %s lista en http://%s (%s, %dx%d)\n", RED_NOMBRE, WiFi.softAPIP().toString().c_str(), sensorNombre, W, H);

  iniciarServidor();
  led(false);
}

void loop() {
  const uint32_t t0 = millis();
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) { delay(30); return; }
  bool ok = false;
  if (fb->format == PIXFORMAT_JPEG && fb->len <= CAP_JPG && fb->width == W && fb->height == H) {
    xSemaphoreTake(mux, portMAX_DELAY);
    memcpy(ultimo, fb->buf, fb->len); ultimoLen = fb->len;
    xSemaphoreGive(mux);
    ok = jpg2rgb565(fb->buf, fb->len, rgb, JPG_SCALE_8X);
  }
  esp_camera_fb_return(fb);
  if (ok) { aGris(); vigilar(); }

  led(millis() < ledHasta);
  // Unas 5 imágenes por segundo bastan y la placa se calienta menos
  const uint32_t dt = millis() - t0;
  if (dt < 200) delay(200 - dt);
}
