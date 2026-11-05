package es.pablo.lectordianas;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor HTTP mínimo con las mismas rutas que el firmware del ESP32, para que el Puntuador
 * no note la diferencia: /estado, /foto[?seq=N], /referencia, /ajustes?sens=…
 */
final class Servidor {

    static final int PUERTO = 8080;

    interface Rutas {
        String estado();

        /** Foto del aviso seq (si es el último) o, con seq &lt; 0, una foto nueva. */
        byte[] foto(long seq);

        void referencia();

        void ajustes(Map<String, String> q);

        void cliente(String ip);
    }

    private final Rutas rutas;
    private final ExecutorService hilos = Executors.newCachedThreadPool();
    private ServerSocket socket;
    private volatile boolean activo = false;

    Servidor(Rutas rutas) {
        this.rutas = rutas;
    }

    synchronized void iniciar() {
        if (activo) return;
        activo = true;
        new Thread(() -> {
            while (activo) {
                try (ServerSocket s = new ServerSocket(PUERTO)) {
                    s.setReuseAddress(true);
                    socket = s;
                    while (activo) {
                        Socket c = s.accept();
                        hilos.execute(() -> atender(c));
                    }
                } catch (IOException e) {
                    if (activo) dormir(1000);
                }
            }
        }, "servidor").start();
    }

    synchronized void parar() {
        activo = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private void atender(Socket c) {
        try (Socket sc = c) {
            sc.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(sc.getInputStream());
            String linea = leerLinea(in);
            if (linea == null) return;
            // Cabeceras (se ignoran)
            String h;
            while ((h = leerLinea(in)) != null && !h.isEmpty()) {
            }
            String[] partes = linea.split(" ");
            if (partes.length < 2) return;
            String ruta = partes[1], consulta = "";
            int q = ruta.indexOf('?');
            if (q >= 0) {
                consulta = ruta.substring(q + 1);
                ruta = ruta.substring(0, q);
            }
            Map<String, String> params = parsear(consulta);
            rutas.cliente(sc.getInetAddress().getHostAddress());
            OutputStream out = sc.getOutputStream();
            switch (ruta) {
                case "/estado":
                    enviar(out, 200, "application/json", rutas.estado().getBytes(StandardCharsets.UTF_8), null);
                    break;
                case "/foto": {
                    long seq = -1;
                    try {
                        if (params.containsKey("seq")) seq = Long.parseLong(params.get("seq"));
                    } catch (NumberFormatException ignored) {
                    }
                    byte[] f = rutas.foto(seq);
                    if (f == null) enviar(out, 503, "text/plain", "sin foto".getBytes(StandardCharsets.UTF_8), null);
                    else enviar(out, 200, "image/jpeg", f, "X-Seq: " + Math.max(seq, 0));
                    break;
                }
                case "/referencia":
                    rutas.referencia();
                    enviar(out, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), null);
                    break;
                case "/ajustes":
                    rutas.ajustes(params);
                    enviar(out, 200, "application/json", rutas.estado().getBytes(StandardCharsets.UTF_8), null);
                    break;
                case "/":
                    enviar(out, 200, "text/html; charset=utf-8", PAGINA.getBytes(StandardCharsets.UTF_8), null);
                    break;
                default:
                    enviar(out, 404, "text/plain", "no".getBytes(StandardCharsets.UTF_8), null);
            }
        } catch (Exception ignored) {
        }
    }

    private static void enviar(OutputStream out, int codigo, String tipo, byte[] cuerpo, String extra) throws IOException {
        String estado = codigo == 200 ? "OK" : codigo == 404 ? "Not Found" : "Service Unavailable";
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(codigo).append(' ').append(estado).append("\r\n");
        sb.append("Content-Type: ").append(tipo).append("\r\n");
        sb.append("Content-Length: ").append(cuerpo.length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Expose-Headers: X-Seq\r\n");
        sb.append("Cache-Control: no-store\r\n");
        if (extra != null) sb.append(extra).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(cuerpo);
        out.flush();
    }

    private static String leerLinea(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 4096) break;
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("US-ASCII");
    }

    private static Map<String, String> parsear(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String par : q.split("&")) {
            int i = par.indexOf('=');
            try {
                if (i > 0) m.put(URLDecoder.decode(par.substring(0, i), "UTF-8"), URLDecoder.decode(par.substring(i + 1), "UTF-8"));
                else m.put(URLDecoder.decode(par, "UTF-8"), "");
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static final String PAGINA = "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Lector de dianas</title>"
            + "<style>body{font-family:system-ui,sans-serif;margin:0;background:#0e1213;color:#e8ebe6}main{padding:12px}"
            + "img{width:100%;border-radius:8px;background:#222}</style></head><body><main><h2>Lector de dianas</h2>"
            + "<p id=\"e\">…</p><img id=\"f\" alt=\"\"></main><script>"
            + "const $=i=>document.getElementById(i);"
            + "setInterval(()=>fetch('/estado').then(r=>r.json()).then(j=>{$('e').textContent=j.placa+' · '+j.ancho+'×'+j.alto+' · aviso '+j.seq+' ('+j.tipo+')'}).catch(()=>{}),1500);"
            + "function foto(){const i=new Image();i.onload=()=>{$('f').src=i.src;setTimeout(foto,1500)};i.onerror=()=>setTimeout(foto,3000);i.src='/foto?t='+Date.now()}foto();"
            + "</script></body></html>";
}
