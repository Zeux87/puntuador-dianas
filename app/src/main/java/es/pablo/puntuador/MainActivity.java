package es.pablo.puntuador;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String REPO = "Zeux87/puntuador-dianas";
    private static final String API_ULTIMA = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static final int REQ_CAMARA = 10;
    private static final int REQ_GALERIA = 11;
    private static final int REQ_EXPORTAR = 12;
    private static final int REQ_IMPORTAR = 13;

    private WebView web;
    private ValueCallback<Uri[]> callback;
    private Uri fotoUri;

    // Datos de la última versión encontrada en GitHub
    private volatile String urlApk = null;
    private volatile String versionNueva = null;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);

        web.addJavascriptInterface(new Puente(), "AndroidApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (callback != null) callback.onReceiveValue(null);
                callback = cb;
                if (p.isCaptureEnabled()) abrirCamara();
                else abrirGaleria();
                return true;
            }
        });

        aplicarTema("sistema");
        web.loadUrl("file:///android_asset/index.html");
    }

    /* ---------------- Tema: colores de las barras del sistema ---------------- */

    private void aplicarTema(String modo) {
        boolean noche = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        boolean oscuro = "oscuro".equals(modo) || (!"claro".equals(modo) && noche);
        int fondo = Color.parseColor(oscuro ? "#0E1213" : "#E8EBE6");
        int superficie = Color.parseColor(oscuro ? "#162022" : "#F7F8F5");
        getWindow().setStatusBarColor(fondo);
        getWindow().setNavigationBarColor(superficie);
        if (web != null) web.setBackgroundColor(fondo);
        View decor = getWindow().getDecorView();
        int f = decor.getSystemUiVisibility();
        int claros = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) claros |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(oscuro ? (f & ~claros) : (f | claros));
    }

    /* ---------------- Cámara y galería ---------------- */

    private void abrirCamara() {
        try {
            File dir = new File(getCacheDir(), "fotos");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "diana_" + System.currentTimeMillis() + ".jpg");
            fotoUri = FileProvider.getUriForFile(this, getPackageName() + ".fotos", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, fotoUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMARA);
        } catch (Exception e) {
            Toast.makeText(this, "No se ha podido abrir la cámara", Toast.LENGTH_SHORT).show();
            abrirGaleria();
        }
    }

    private void abrirGaleria() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Elige la foto de la diana"), REQ_GALERIA);
        } catch (Exception e) {
            if (callback != null) {
                callback.onReceiveValue(null);
                callback = null;
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_EXPORTAR || req == REQ_IMPORTAR) {
            if (res != RESULT_OK || data == null || data.getData() == null) {
                avisarCopia(false, "Cancelado.", false);
            } else if (req == REQ_EXPORTAR) {
                escribirCopia(data.getData());
            } else {
                leerCopia(data.getData());
            }
            return;
        }
        if (callback == null) return;
        Uri[] resultado = null;
        if (res == RESULT_OK) {
            if (req == REQ_CAMARA && fotoUri != null) {
                resultado = new Uri[]{fotoUri};
            } else if (req == REQ_GALERIA && data != null && data.getData() != null) {
                resultado = new Uri[]{data.getData()};
            }
        }
        callback.onReceiveValue(resultado);
        callback = null;
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        // La página decide si el botón atrás vuelve a una vista anterior; si no, se sale de la app
        web.evaluateJavascript("(window.onAndroidBack && window.onAndroidBack()) ? 1 : 0", v -> {
            if (!"1".equals(v)) finish();
        });
    }

    /* ---------------- Almacenamiento de sesiones y fotos ---------------- */

    private File dirDatos() {
        File d = new File(getFilesDir(), "datos");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static boolean claveValida(String k) {
        return k != null && k.matches("[A-Za-z0-9_\\-]{1,80}");
    }

    private synchronized String leerDato(String clave) {
        if (!claveValida(clave)) return null;
        File f = new File(dirDatos(), clave);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return leerTodo(in);
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized boolean escribirDato(String clave, String valor) {
        if (!claveValida(clave) || valor == null) return false;
        File dir = dirDatos();
        File tmp = new File(dir, clave + ".tmp");
        File destino = new File(dir, clave);
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(valor.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
        // Escritura atómica: si algo falla a medias, el archivo anterior sigue intacto
        if (destino.exists() && !destino.delete()) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(destino);
    }

    private synchronized void borrarDato(String clave) {
        if (!claveValida(clave)) return;
        new File(dirDatos(), clave).delete();
    }

    /* ---------------- Copia de seguridad ---------------- */

    private void avisarCopia(boolean ok, String msg, boolean importada) {
        try {
            JSONObject r = new JSONObject();
            r.put("ok", ok);
            r.put("msg", msg);
            r.put("importada", importada);
            llamarJs("onBackupDone", r.toString());
        } catch (Exception ignored) {
        }
    }

    private void exportarCopia() {
        String fecha = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "puntuador-copia-" + fecha + ".json");
        runOnUiThread(() -> {
            try {
                startActivityForResult(i, REQ_EXPORTAR);
            } catch (Exception e) {
                avisarCopia(false, "No se ha podido abrir el selector de archivos.", false);
            }
        });
    }

    private void importarCopia() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        runOnUiThread(() -> {
            try {
                startActivityForResult(i, REQ_IMPORTAR);
            } catch (Exception e) {
                avisarCopia(false, "No se ha podido abrir el selector de archivos.", false);
            }
        });
    }

    // Escribe todas las sesiones y fotos en un archivo JSON, sin cargarlo entero en memoria
    private void escribirCopia(Uri uri) {
        new Thread(() -> {
            int sesiones = 0;
            try (OutputStream os = getContentResolver().openOutputStream(uri, "w");
                 JsonWriter w = new JsonWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                w.beginObject();
                w.name("app").value("puntuador-dianas");
                w.name("formato").value(1);
                w.name("fecha").value(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()));
                w.name("version").value(nombreVersion());
                w.name("datos").beginObject();
                File[] archivos = dirDatos().listFiles();
                if (archivos != null) {
                    for (File f : archivos) {
                        String k = f.getName();
                        if (!claveValida(k) || "activa".equals(k)) continue;
                        String v = leerDato(k);
                        if (v == null) continue;
                        if (k.startsWith("ses_")) sesiones++;
                        w.name(k).value(v);
                    }
                }
                w.endObject();
                w.endObject();
            } catch (Exception e) {
                avisarCopia(false, "No se ha podido guardar la copia. Prueba en otra carpeta.", false);
                return;
            }
            avisarCopia(true, "Copia guardada con " + sesiones + (sesiones == 1 ? " sesión." : " sesiones."), false);
        }).start();
    }

    private void leerCopia(Uri uri) {
        new Thread(() -> {
            int sesiones = 0;
            try (InputStream is = getContentResolver().openInputStream(uri);
                 JsonReader r = new JsonReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                boolean esNuestra = false;
                String indice = null;
                r.beginObject();
                while (r.hasNext()) {
                    String n = r.nextName();
                    if ("app".equals(n) && r.peek() == JsonToken.STRING) {
                        esNuestra = "puntuador-dianas".equals(r.nextString());
                    } else if ("datos".equals(n)) {
                        if (!esNuestra) throw new IllegalArgumentException();
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            if (r.peek() != JsonToken.STRING) {
                                r.skipValue();
                                continue;
                            }
                            String v = r.nextString();
                            if (!claveValida(k) || "activa".equals(k)) continue;
                            if ("sesiones".equals(k)) {
                                indice = v;
                                continue;
                            }
                            if (escribirDato(k, v) && k.startsWith("ses_")) sesiones++;
                        }
                        r.endObject();
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
                if (!esNuestra) throw new IllegalArgumentException();
                if (indice != null) fusionarIndice(indice);
            } catch (IllegalArgumentException e) {
                avisarCopia(false, "Ese archivo no es una copia del Puntuador de dianas.", false);
                return;
            } catch (Exception e) {
                avisarCopia(false, "No se ha podido leer la copia. Comprueba que el archivo está completo.", false);
                return;
            }
            avisarCopia(true, "Copia restaurada: " + sesiones + (sesiones == 1 ? " sesión." : " sesiones."), true);
        }).start();
    }

    // Une el listado de sesiones de la copia con el actual; si una sesión está en los dos, gana la de la copia
    private synchronized void fusionarIndice(String nuevo) throws Exception {
        LinkedHashMap<String, JSONObject> m = new LinkedHashMap<>();
        String actual = leerDato("sesiones");
        if (actual != null) {
            JSONArray a = new JSONArray(actual);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                m.put(o.optString("id"), o);
            }
        }
        JSONArray b = new JSONArray(nuevo);
        for (int i = 0; i < b.length(); i++) {
            JSONObject o = b.getJSONObject(i);
            m.put(o.optString("id"), o);
        }
        List<JSONObject> lista = new ArrayList<>(m.values());
        lista.sort((x, y) -> y.optString("creada").compareTo(x.optString("creada")));
        JSONArray out = new JSONArray();
        for (JSONObject o : lista) out.put(o);
        escribirDato("sesiones", out.toString());
    }

    /* ---------------- Versión instalada ---------------- */

    private long codigoVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    private String nombreVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /* ---------------- Comunicación con la página ---------------- */

    private void llamarJs(final String funcion, final String argumentoJson) {
        web.post(() -> web.evaluateJavascript(
                "window." + funcion + " && window." + funcion + "(" + argumentoJson + ")", null));
    }

    private static String textoJs(String s) {
        return JSONObject.quote(s);
    }

    private static String leerTodo(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private HttpURLConnection abrir(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PuntuadorDianas");
        return c;
    }

    /* ---------------- Buscar actualización ---------------- */

    private void buscarActualizacion() {
        new Thread(() -> {
            JSONObject r = new JSONObject();
            try {
                r.put("current", nombreVersion());
                HttpURLConnection c = abrir(API_ULTIMA);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                int code = c.getResponseCode();
                if (code == 404) {
                    r.put("error", "No encuentro versiones publicadas. Comprueba que el repositorio de GitHub es público.");
                    llamarJs("onUpdateInfo", r.toString());
                    return;
                }
                if (code != 200) {
                    r.put("error", "GitHub no responde ahora mismo (código " + code + "). Prueba más tarde.");
                    llamarJs("onUpdateInfo", r.toString());
                    return;
                }
                JSONObject rel = new JSONObject(leerTodo(c.getInputStream()));
                String tag = rel.optString("tag_name", "");
                String nombre = tag.startsWith("v") ? tag.substring(1) : tag;
                long numero = 0;
                int punto = nombre.lastIndexOf('.');
                try {
                    numero = Long.parseLong(punto >= 0 ? nombre.substring(punto + 1) : nombre);
                } catch (Exception ignored) {
                }
                String apk = null;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name", "").endsWith(".apk")) {
                            apk = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                boolean hay = numero > codigoVersion() && apk != null;
                urlApk = hay ? apk : null;
                versionNueva = hay ? nombre : null;
                r.put("available", hay);
                r.put("latest", nombre);
            } catch (Exception e) {
                try {
                    r.put("error", "No hay conexión con GitHub. Revisa internet y vuelve a probar.");
                } catch (Exception ignored) {
                }
            }
            llamarJs("onUpdateInfo", r.toString());
        }).start();
    }

    /* ---------------- Descargar e instalar ---------------- */

    private void instalarActualizacion() {
        final String url = urlApk;
        if (url == null) {
            llamarJs("onUpdateDone", textoJs("Primero busca actualizaciones."));
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                }
            });
            llamarJs("onUpdateDone", textoJs("Activa «Permitir de esta fuente» para Puntuador, vuelve atrás y pulsa Actualizar otra vez."));
            return;
        }
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "actualizaciones");
                if (!dir.exists()) dir.mkdirs();
                File[] viejos = dir.listFiles();
                if (viejos != null) for (File v : viejos) v.delete();
                File destino = new File(dir, "PuntuadorDianas-" + versionNueva + ".apk");

                HttpURLConnection c = abrir(url);
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(destino)) {
                    byte[] buf = new byte[16384];
                    long leido = 0;
                    int ultimo = -1, n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        leido += n;
                        if (total > 0) {
                            int pct = (int) (leido * 100 / total);
                            if (pct >= ultimo + 5) {
                                ultimo = pct;
                                llamarJs("onUpdateProgress", String.valueOf(pct));
                            }
                        }
                    }
                }

                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fotos", destino);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                runOnUiThread(() -> startActivity(i));
                llamarJs("onUpdateDone", textoJs("Descargada. Confirma la instalación en la ventana de Android."));
            } catch (Exception e) {
                llamarJs("onUpdateDone", textoJs("No se ha podido descargar la actualización. Revisa la conexión y vuelve a probar."));
            }
        }).start();
    }

    /* ---------------- Funciones visibles desde la página ---------------- */

    private class Puente {
        @JavascriptInterface
        public void copy(final String texto) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Puntuación", texto));
                Toast.makeText(MainActivity.this, "Resumen copiado", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public String storeGet(String clave) {
            return leerDato(clave);
        }

        @JavascriptInterface
        public boolean storeSet(String clave, String valor) {
            return escribirDato(clave, valor);
        }

        @JavascriptInterface
        public void storeDelete(String clave) {
            borrarDato(clave);
        }

        @JavascriptInterface
        public void exportBackup() {
            exportarCopia();
        }

        @JavascriptInterface
        public void importBackup() {
            importarCopia();
        }

        @JavascriptInterface
        public void setTheme(final String modo) {
            runOnUiThread(() -> aplicarTema(modo));
        }

        @JavascriptInterface
        public String versionName() {
            return nombreVersion();
        }

        @JavascriptInterface
        public void checkUpdate() {
            buscarActualizacion();
        }

        @JavascriptInterface
        public void installUpdate() {
            instalarActualizacion();
        }
    }
}
