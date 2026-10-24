package es.pablo.puntuador;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String REPO = "Zeux87/puntuador-dianas";
    private static final String API_ULTIMA = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static final int REQ_CAMARA = 10;
    private static final int REQ_GALERIA = 11;

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

        web.loadUrl("file:///android_asset/index.html");
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
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
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
