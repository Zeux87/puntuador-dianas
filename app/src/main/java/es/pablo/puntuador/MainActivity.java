package es.pablo.puntuador;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

public class MainActivity extends Activity {

    private static final int REQ_CAMARA = 10;
    private static final int REQ_GALERIA = 11;

    private WebView web;
    private ValueCallback<Uri[]> callback;
    private Uri fotoUri;

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

    private class Puente {
        @JavascriptInterface
        public void copy(final String texto) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Puntuación", texto));
                Toast.makeText(MainActivity.this, "Resumen copiado", Toast.LENGTH_SHORT).show();
            });
        }
    }
}
