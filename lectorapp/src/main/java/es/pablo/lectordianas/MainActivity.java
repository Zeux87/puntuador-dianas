package es.pablo.lectordianas;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileInputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lector de dianas: convierte un móvil viejo en la cámara que vigila la diana.
 * Habla igual que el lector ESP32 (mismas rutas en el puerto 8080), así que el Puntuador
 * lo usa sin cambios: cada impacto llega con su foto y se marca en orden.
 */
public class MainActivity extends ComponentActivity {

    static final String RED = "LineaDeTiro-Lector";
    static final String CLAVE = "lineadetiro";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exAnalisis = Executors.newSingleThreadExecutor();
    private final ExecutorService exFoto = Executors.newSingleThreadExecutor();
    private final ExecutorService exGuardar = Executors.newSingleThreadExecutor();
    private final Object capLock = new Object();
    private final Object mux = new Object();
    private final long arranque = System.currentTimeMillis();

    private SharedPreferences prefs;
    private PreviewView vista;
    private LinearLayout panel;
    private TextView txtRed, txtEstado, txtZoom, txtSens;
    private Button bLuz;

    private ProcessCameraProvider proveedor;
    private Camera camara;
    private ImageCapture captura;
    private Vigilante vig;
    private Servidor srv;

    // Último aviso
    private volatile long seq = 0;
    private String tipo = "nada";
    private float avX, avY;
    private int avPix;
    private boolean avManual;
    private long msAviso = System.currentTimeMillis();
    private byte[] evFoto;
    private int fotoW, fotoH;

    private volatile int anRot = 0;
    private volatile long tAnalisis = 0;
    private volatile String siguienteTipo = null;      // tipo del aviso que sigue a un reajuste
    private volatile boolean luz = false;
    private volatile String cliente = null;
    private volatile long tCliente = 0;
    private int impactos = 0;
    private boolean oscuro = false;

    private final ActivityResultLauncher<String> permiso =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
                if (ok) iniciarCamara();
                else txtEstado.setText("Sin permiso para la cámara. Dáselo en los ajustes de Android.");
            });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("lector", MODE_PRIVATE);
        construirInterfaz();

        vig = new Vigilante(this::alAviso);
        vig.setSens(prefs.getInt("sens", 50));
        srv = new Servidor(rutas);
        srv.iniciar();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) iniciarCamara();
        else permiso.launch(Manifest.permission.CAMERA);

        ui.post(refresco);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        srv.parar();
        super.onDestroy();
    }

    /* ======================= Cámara ======================= */

    private static ResolutionSelector res43(int w, int h) {
        return new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(new ResolutionStrategy(new Size(w, h),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build();
    }

    private void iniciarCamara() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                proveedor = f.get();
                enlazar();
            } catch (Exception e) {
                txtEstado.setText("No puedo abrir la cámara: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void enlazar() {
        Preview prev = new Preview.Builder().setResolutionSelector(res43(1280, 960)).build();
        prev.setSurfaceProvider(vista.getSurfaceProvider());

        captura = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setJpegQuality(88)
                .setResolutionSelector(res43(2592, 1944))
                .build();

        ImageAnalysis analisis = new ImageAnalysis.Builder()
                .setResolutionSelector(res43(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analisis.setAnalyzer(exAnalisis, this::analizar);

        vig.pausar(true);                      // no vigila hasta haber enfocado
        proveedor.unbindAll();
        camara = proveedor.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, captura, analisis);

        float z = prefs.getFloat("zoom", 0f);
        camara.getCameraControl().setLinearZoom(z);
        bLuz.setVisibility(camara.getCameraInfo().hasFlashUnit() ? View.VISIBLE : View.GONE);
        txtEstado.setText("Preparando la cámara…");
        ui.postDelayed(() -> fijar(null, false), 1500);
    }

    private void analizar(ImageProxy img) {
        try {
            long t = System.currentTimeMillis();
            if (t - tAnalisis < 160) return;          // unas 6 imágenes por segundo
            tAnalisis = t;
            ImageProxy.PlaneProxy p = img.getPlanes()[0];
            anRot = img.getImageInfo().getRotationDegrees();
            vig.procesar(p.getBuffer(), img.getWidth(), img.getHeight(), p.getRowStride(), p.getPixelStride());
        } catch (Exception ignored) {
        } finally {
            img.close();
        }
    }

    /** Enfoca y mide la luz en un punto (el centro si es null) y deja enfoque, exposición y blancos fijos. */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void fijar(MeteringPoint punto, boolean encuadreCambiado) {
        if (camara == null) return;
        vig.pausar(true);
        if (seq > 0) siguienteTipo = encuadreCambiado ? "cambio" : "ajuste";
        txtEstado.setText("Enfocando…");
        Camera2CameraControl c2 = Camera2CameraControl.from(camara.getCameraControl());
        c2.clearCaptureRequestOptions();
        MeteringPoint p = punto != null ? punto : vista.getMeteringPointFactory().createPoint(vista.getWidth() / 2f, vista.getHeight() / 2f);
        FocusMeteringAction a = new FocusMeteringAction.Builder(p,
                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB)
                .disableAutoCancel()
                .build();
        ListenableFuture<?> r = camara.getCameraControl().startFocusAndMetering(a);
        r.addListener(() -> ui.postDelayed(() -> {
            try {
                c2.setCaptureRequestOptions(new CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                        .build());
            } catch (Exception ignored) {
            }
            ui.postDelayed(() -> {
                vig.pausar(false);
                txtEstado.setText("Enfoque fijado. Vigilando la diana…");
            }, 700);
        }, 600), ContextCompat.getMainExecutor(this));
    }

    /** Foto a resolución completa (bloquea hasta que está hecha). No llamar desde el hilo principal. */
    private byte[] hacerFoto(long espera) {
        synchronized (capLock) {
            ImageCapture c = captura;
            if (c == null) return null;
            File f = new File(getCacheDir(), "foto.jpg");
            if (f.exists()) f.delete();
            ImageCapture.OutputFileOptions o = new ImageCapture.OutputFileOptions.Builder(f).build();
            CountDownLatch hecho = new CountDownLatch(1);
            final boolean[] ok = {false};
            ui.post(() -> c.takePicture(o, exGuardar, new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(ImageCapture.OutputFileResults r) {
                    ok[0] = true;
                    hecho.countDown();
                }

                @Override
                public void onError(ImageCaptureException e) {
                    hecho.countDown();
                }
            }));
            try {
                if (!hecho.await(espera, TimeUnit.MILLISECONDS) || !ok[0]) return null;
                ExifInterface ex = new ExifInterface(f.getAbsolutePath());
                int w = ex.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0);
                int h = ex.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0);
                if (w <= 0 || h <= 0) {
                    android.graphics.BitmapFactory.Options op = new android.graphics.BitmapFactory.Options();
                    op.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), op);
                    w = op.outWidth;
                    h = op.outHeight;
                }
                int giro = ex.getRotationDegrees();
                if (giro == 90 || giro == 270) { int t = w; w = h; h = t; }
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int n = 0, k;
                    while (n < b.length && (k = in.read(b, n, b.length - n)) > 0) n += k;
                }
                synchronized (mux) {
                    fotoW = w;
                    fotoH = h;
                }
                return b;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /* ======================= Avisos ======================= */

    private void alAviso(String t, float x, float y, int pix, boolean manual) {
        String tipoFinal = t;
        boolean man = manual;
        if ("inicio".equals(t)) {
            if (seq == 0) tipoFinal = "inicio";
            else {
                tipoFinal = siguienteTipo != null ? siguienteTipo : "ajuste";
                man = "cambio".equals(tipoFinal);
            }
            siguienteTipo = null;
        }
        // Posición en la foto ya girada (la imagen de análisis va en la orientación del sensor)
        float ux = x, uy = y;
        switch (anRot) {
            case 90: ux = 1 - y; uy = x; break;
            case 180: ux = 1 - x; uy = 1 - y; break;
            case 270: ux = y; uy = 1 - x; break;
        }
        final String tf = tipoFinal;
        final boolean mf = man;
        final float fx = ux, fy = uy;
        exFoto.execute(() -> {
            byte[] b = hacerFoto(5000);
            if (b == null) return;
            synchronized (mux) {
                evFoto = b;
                seq++;
                tipo = tf;
                avX = fx;
                avY = fy;
                avPix = pix;
                avManual = mf;
                msAviso = System.currentTimeMillis();
            }
            ui.post(() -> {
                if ("impacto".equals(tf)) {
                    impactos++;
                    txtEstado.setText("Impacto " + impactos + " detectado y enviado.");
                } else if ("cambio".equals(tf)) {
                    impactos = 0;
                    txtEstado.setText("Diana nueva.");
                } else if ("inicio".equals(tf)) {
                    txtEstado.setText("Vigilando la diana…");
                }
            });
        });
    }

    private final Servidor.Rutas rutas = new Servidor.Rutas() {
        @Override
        public String estado() {
            synchronized (mux) {
                return String.format(Locale.US,
                        "{\"v\":\"app-%s\",\"placa\":\"%s\",\"sensor\":\"cámara del móvil\",\"ancho\":%d,\"alto\":%d,"
                                + "\"seq\":%d,\"tipo\":\"%s\",\"x\":%.4f,\"y\":%.4f,\"pix\":%d,\"manual\":%s,\"hace\":%d,"
                                + "\"sens\":%d,\"giro\":0,\"luz\":%d,\"hayLuz\":%s,\"vigilando\":%s,\"uptime\":%d,\"puerto\":%d}",
                        version(), json("Móvil " + Build.MANUFACTURER + " " + Build.MODEL), fotoW, fotoH,
                        seq, tipo, avX, avY, avPix, avManual ? "true" : "false", System.currentTimeMillis() - msAviso,
                        vig.getSens(), luz ? 1 : 0, (camara != null && camara.getCameraInfo().hasFlashUnit()) ? "true" : "false",
                        vig.vigilando() ? "true" : "false", (System.currentTimeMillis() - arranque) / 1000, Servidor.PUERTO);
            }
        }

        @Override
        public byte[] foto(long s) {
            synchronized (mux) {
                if (s >= 0 && s == seq && evFoto != null) return evFoto;
            }
            return hacerFoto(5000);
        }

        @Override
        public void referencia() {
            vig.referencia();
        }

        @Override
        public void ajustes(Map<String, String> q) {
            try {
                if (q.containsKey("sens")) {
                    int s = Integer.parseInt(q.get("sens"));
                    vig.setSens(s);
                    prefs.edit().putInt("sens", vig.getSens()).apply();
                    ui.post(() -> pintarAjustes());
                }
                if (q.containsKey("luz")) {
                    boolean on = !"0".equals(q.get("luz"));
                    ui.post(() -> ponerLuz(on));
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void cliente(String ip) {
            cliente = ip;
            tCliente = System.currentTimeMillis();
        }
    };

    private void ponerLuz(boolean on) {
        if (camara == null || !camara.getCameraInfo().hasFlashUnit()) return;
        luz = on;
        camara.getCameraControl().enableTorch(on);
        bLuz.setText(on ? "Apagar luz" : "Encender luz");
        ui.postDelayed(() -> fijar(null, false), 500);
    }

    /* ======================= Interfaz ======================= */

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView texto(float sp, boolean negrita) {
        TextView t = new TextView(this);
        t.setTextColor(Color.rgb(232, 235, 230));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (negrita) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button boton(String txt, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(txt);
        b.setAllCaps(false);
        b.setTextColor(Color.rgb(232, 235, 230));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.rgb(22, 32, 34));
        g.setStroke(dp(1), Color.rgb(22, 205, 225));
        g.setCornerRadius(dp(8));
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private SeekBar barra(int valor, SeekBar.OnSeekBarChangeListener l) {
        SeekBar s = new SeekBar(this);
        s.setMax(100);
        s.setProgress(valor);
        s.setOnSeekBarChangeListener(l);
        return s;
    }

    private void construirInterfaz() {
        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.rgb(14, 18, 19));

        vista = new PreviewView(this);
        vista.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        vista.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        vista.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                if (oscuro) {
                    pantallaOscura(false);
                } else if (camara != null) {
                    fijar(vista.getMeteringPointFactory().createPoint(e.getX(), e.getY()), false);
                }
                v.performClick();
            }
            return true;
        });
        FrameLayout.LayoutParams lpV = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lpV.rightMargin = dp(250);
        raiz.addView(vista, lpV);

        ScrollView sc = new ScrollView(this);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        sc.addView(panel);
        sc.setBackgroundColor(Color.argb(235, 14, 18, 19));
        FrameLayout.LayoutParams lpP = new FrameLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        raiz.addView(sc, lpP);

        TextView titulo = texto(18, true);
        titulo.setText("Lector de dianas");
        panel.addView(titulo);

        txtRed = texto(13, false);
        panel.addView(txtRed);
        txtEstado = texto(14, true);
        txtEstado.setTextColor(Color.rgb(22, 205, 225));
        panel.addView(txtEstado);

        panel.addView(boton("Diana nueva", v -> vig.referencia()));
        panel.addView(boton("Volver a enfocar", v -> fijar(null, false)));
        bLuz = boton("Encender luz", v -> ponerLuz(!luz));
        bLuz.setVisibility(View.GONE);
        panel.addView(bLuz);
        panel.addView(boton("Pantalla oscura", v -> pantallaOscura(true)));
        panel.addView(boton("Zona WiFi del móvil", v -> abrirZonaWifi()));

        txtZoom = texto(13, false);
        panel.addView(txtZoom);
        panel.addView(barra(Math.round(prefs.getFloat("zoom", 0f) * 100), new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                if (camara != null) camara.getCameraControl().setLinearZoom(p / 100f);
                prefs.edit().putFloat("zoom", p / 100f).apply();
                pintarAjustes();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                vig.pausar(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                fijar(null, true);
            }
        }));

        txtSens = texto(13, false);
        panel.addView(txtSens);
        panel.addView(barra(prefs.getInt("sens", 50), new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                vig.setSens(p);
                prefs.edit().putInt("sens", p).apply();
                pintarAjustes();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        }));

        TextView ayuda = texto(12, false);
        ayuda.setAlpha(0.75f);
        ayuda.setText("1. Activa la zona WiFi de este móvil con el nombre " + RED + " y la clave " + CLAVE
                + " (seguridad WPA2).\n2. Encuadra la diana con el zoom y toca la imagen para enfocar sobre ella.\n"
                + "3. En el Puntuador, elige «Con el lector WiFi».\nLa diana se puede ver girada; lo importante es que se vea entera y nítida.");
        panel.addView(ayuda);

        setContentView(raiz);
        pintarAjustes();
    }

    private void pintarAjustes() {
        txtZoom.setText(String.format(Locale.getDefault(), "Zoom: %d %%", Math.round(prefs.getFloat("zoom", 0f) * 100)));
        txtSens.setText("Sensibilidad: " + vig.getSens());
    }

    private void pantallaOscura(boolean si) {
        oscuro = si;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = si ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        ((View) panel.getParent()).setVisibility(si ? View.GONE : View.VISIBLE);
        vista.setAlpha(si ? 0.15f : 1f);
    }

    private void abrirZonaWifi() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(new ComponentName("com.android.settings", "com.android.settings.TetherSettings"));
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private final Runnable refresco = new Runnable() {
        @Override
        public void run() {
            StringBuilder sb = new StringBuilder();
            List<String> ips = ipsLocales();
            if (ips.isEmpty()) sb.append("Sin red. Activa la zona WiFi del móvil.");
            else sb.append("Dirección: ").append(android.text.TextUtils.join(", ", ips)).append(" · puerto ").append(Servidor.PUERTO);
            long hace = System.currentTimeMillis() - tCliente;
            sb.append('\n').append(cliente != null && hace < 5000 ? "Puntuador conectado (" + cliente + ")" : "Puntuador sin conectar");
            txtRed.setText(sb.toString());
            ui.postDelayed(this, 1000);
        }
    };

    private static List<String> ipsLocales() {
        List<String> r = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String s = a.getHostAddress();
                        if (s != null && !r.contains(s)) r.add(s);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
