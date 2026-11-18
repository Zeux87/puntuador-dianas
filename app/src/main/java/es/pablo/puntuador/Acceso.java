package es.pablo.puntuador;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.CancellationSignal;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Control de acceso: inicio de sesión con Google y lista de cuentas permitidas.
 *
 * La lista está en el archivo permitidos.txt del repositorio, así se puede cambiar sin compilar.
 * Como el repositorio es público, la lista no guarda correos sino un código de cada correo
 * (los 16 primeros caracteres del SHA-256 de "puntuador-dianas:" + correo en minúsculas).
 * Una línea con "*" deja entrar a cualquiera (sirve de salida de emergencia si el inicio de sesión falla).
 */
class Acceso {

    // ID de cliente web del proyecto «Puntuador dianas» de Google Cloud
    static final String CLIENTE_WEB = "895728224312-dv580ocdo9644i51v7njdmon9lhbuvss.apps.googleusercontent.com";
    private static final String URL_LISTA = "https://raw.githubusercontent.com/Zeux87/puntuador-dianas/main/permitidos.txt";
    private static final String SAL = "puntuador-dianas:";

    private static final String PREFS = "acceso";
    private static final String K_CORREO = "correo";
    private static final String K_PERMITIDO = "permitido";

    private final Activity act;
    private final Runnable alPermitir;
    private final SharedPreferences prefs;
    private final CredentialManager credenciales;

    private ScrollView vista;
    private TextView mensaje;
    private TextView codigo;
    private Button entrar;
    private Button copiar;
    private ProgressBar rueda;
    private boolean ocupado = false;

    Acceso(Activity act, Runnable alPermitir) {
        this.act = act;
        this.alPermitir = alPermitir;
        this.prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.credenciales = CredentialManager.create(act);
    }

    /** Correo de la cuenta con la que se ha entrado ("" si se entró sin cuenta o no hay sesión). */
    String correo() {
        return prefs.getString(K_CORREO, "");
    }

    /** true si la pantalla de acceso está oculta y se ve la app. */
    boolean dejaVerLaApp() {
        return vista != null && vista.getVisibility() != View.VISIBLE;
    }

    /** Se llama al abrir la app. */
    void empezar(FrameLayout raiz) {
        construirVista(raiz);
        if (prefs.getBoolean(K_PERMITIDO, false)) {
            // Ya tenía acceso: se abre al momento y la lista se vuelve a mirar por detrás
            vista.setVisibility(View.GONE);
            alPermitir.run();
            revisarEnSegundoPlano();
        } else {
            mostrar("Para usar la app, entra con tu cuenta de Google.", false);
            // Si la lista está abierta a todos, no hace falta iniciar sesión
            new Thread(() -> {
                Set<String> lista = descargarLista();
                if (lista != null && lista.contains("*")) {
                    act.runOnUiThread(() -> conceder(""));
                }
            }).start();
        }
    }

    /** Cerrar sesión desde Ajustes. */
    void cerrarSesion() {
        prefs.edit().clear().apply();
        limpiarCredencial();
        act.runOnUiThread(() -> mostrar("Has cerrado sesión. Entra con tu cuenta de Google para seguir usando la app.", false));
    }

    /* ---------------- Lógica ---------------- */

    private void revisarEnSegundoPlano() {
        final String c = correo();
        new Thread(() -> {
            Set<String> lista = descargarLista();
            if (lista == null) return;   // sin conexión: se mantiene el acceso
            if (lista.contains("*")) return;
            if (!c.isEmpty() && lista.contains(codigoDe(c))) return;
            prefs.edit().clear().apply();
            act.runOnUiThread(() -> {
                if (c.isEmpty()) {
                    mostrar("Ahora hace falta entrar con tu cuenta de Google.", false);
                } else {
                    mostrar("La cuenta " + c + " ya no tiene acceso a la app.", false);
                    mostrarCodigo(c);
                }
            });
        }).start();
    }

    private void iniciarSesion() {
        if (ocupado) return;
        ocupado(true, "Abriendo el inicio de sesión de Google…");
        GetSignInWithGoogleOption opcion = new GetSignInWithGoogleOption.Builder(CLIENTE_WEB).build();
        GetCredentialRequest peticion = new GetCredentialRequest.Builder().addCredentialOption(opcion).build();
        credenciales.getCredentialAsync(act, peticion, new CancellationSignal(), act.getMainExecutor(),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse r) {
                        String c = correoDe(r.getCredential());
                        if (c == null) {
                            ocupado(false, "Google no ha devuelto una cuenta válida. Vuelve a probar.");
                            return;
                        }
                        comprobar(c);
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        if (e instanceof GetCredentialCancellationException) {
                            ocupado(false, "Para usar la app, entra con tu cuenta de Google.");
                        } else if (e instanceof NoCredentialException) {
                            ocupado(false, "No hay ninguna cuenta de Google en este móvil. Añade una en Ajustes del móvil → Cuentas y vuelve a probar.");
                        } else {
                            String det = e.getMessage() != null ? e.getMessage() : e.getType();
                            ocupado(false, "No se ha podido iniciar sesión con Google. Revisa la conexión y vuelve a probar.\n\nDetalle: " + det);
                        }
                    }
                });
    }

    private static String correoDe(Credential cr) {
        try {
            if (cr instanceof CustomCredential
                    && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(cr.getType())) {
                GoogleIdTokenCredential g = GoogleIdTokenCredential.createFrom(cr.getData());
                String id = g.getId();
                if (id != null && id.contains("@")) return id.trim().toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void comprobar(final String c) {
        ocupado(true, "Comprobando si " + c + " tiene acceso…");
        new Thread(() -> {
            Set<String> lista = descargarLista();
            act.runOnUiThread(() -> {
                if (lista == null) {
                    ocupado(false, "No se ha podido descargar la lista de acceso. Revisa la conexión y vuelve a probar.");
                } else if (lista.contains("*") || lista.contains(codigoDe(c))) {
                    conceder(c);
                } else {
                    limpiarCredencial();
                    ocupado(false, "La cuenta " + c + " no tiene acceso a la app todavía.\n\nEnvía a Pablo este código para que te dé acceso y vuelve a entrar:");
                    mostrarCodigo(c);
                    entrar.setText("Entrar con otra cuenta");
                }
            });
        }).start();
    }

    private void conceder(String c) {
        prefs.edit().putString(K_CORREO, c).putBoolean(K_PERMITIDO, true).apply();
        ocupado = false;
        vista.setVisibility(View.GONE);
        alPermitir.run();
    }

    private void limpiarCredencial() {
        try {
            credenciales.clearCredentialStateAsync(new ClearCredentialStateRequest(), new CancellationSignal(),
                    act.getMainExecutor(), new CredentialManagerCallback<Void, ClearCredentialException>() {
                        @Override
                        public void onResult(Void v) {
                        }

                        @Override
                        public void onError(ClearCredentialException e) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    /** Devuelve los códigos de la lista, o null si no se ha podido descargar. */
    private static Set<String> descargarLista() {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(URL_LISTA + "?t=" + System.currentTimeMillis()).openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            con.setUseCaches(false);
            con.setRequestProperty("Cache-Control", "no-cache");
            con.setRequestProperty("User-Agent", "PuntuadorDianas");
            if (con.getResponseCode() != 200) return null;
            String texto;
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                texto = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            Set<String> s = new HashSet<>();
            for (String linea : texto.split("\n")) {
                int almohadilla = linea.indexOf('#');
                if (almohadilla >= 0) linea = linea.substring(0, almohadilla);
                linea = linea.trim().toLowerCase(Locale.ROOT);
                if (linea.isEmpty()) continue;
                // Se admite también el correo tal cual, aunque lo normal es poner su código
                s.add(linea.contains("@") ? codigoDe(linea) : linea);
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    static String codigoDe(String correo) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((SAL + correo.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.ROOT, "%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /* ---------------- Pantalla ---------------- */

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.getResources().getDisplayMetrics());
    }

    private void construirVista(FrameLayout raiz) {
        boolean noche = (act.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int fondo = Color.parseColor(noche ? "#0E1213" : "#E8EBE6");
        int texto = Color.parseColor(noche ? "#E6ECEB" : "#162022");
        int suave = Color.parseColor(noche ? "#9FB0AE" : "#4A5A5C");
        int acento = Color.parseColor("#08727E");

        vista = new ScrollView(act);
        vista.setFillViewport(true);
        vista.setBackgroundColor(fondo);
        vista.setClickable(true);   // que los toques no lleguen a la app de debajo

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(32), dp(48), dp(32), dp(48));
        vista.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icono = new ImageView(act);
        icono.setImageResource(R.mipmap.ic_launcher);
        col.addView(icono, new LinearLayout.LayoutParams(dp(96), dp(96)));

        TextView titulo = new TextView(act);
        titulo.setText("Puntuador de dianas");
        titulo.setTextColor(texto);
        titulo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        titulo.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpT.topMargin = dp(20);
        col.addView(titulo, lpT);

        mensaje = new TextView(act);
        mensaje.setTextColor(suave);
        mensaje.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mensaje.setGravity(Gravity.CENTER);
        mensaje.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams lpM = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpM.topMargin = dp(16);
        col.addView(mensaje, lpM);

        codigo = new TextView(act);
        codigo.setTextColor(texto);
        codigo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        codigo.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codigo.setGravity(Gravity.CENTER);
        codigo.setTextIsSelectable(true);
        codigo.setVisibility(View.GONE);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpC.topMargin = dp(16);
        col.addView(codigo, lpC);

        copiar = boton("Copiar código", false, texto, acento);
        copiar.setVisibility(View.GONE);
        copiar.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Código de acceso", codigo.getText()));
            Toast.makeText(act, "Código copiado", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams lpB1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        lpB1.topMargin = dp(12);
        col.addView(copiar, lpB1);

        rueda = new ProgressBar(act);
        rueda.setVisibility(View.GONE);
        LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(dp(36), dp(36));
        lpR.topMargin = dp(24);
        col.addView(rueda, lpR);

        entrar = boton("Entrar con Google", true, texto, acento);
        entrar.setOnClickListener(v -> iniciarSesion());
        LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        lpB.topMargin = dp(28);
        col.addView(entrar, lpB);

        raiz.addView(vista, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private Button boton(String t, boolean principal, int texto, int acento) {
        Button b = new Button(act);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setPadding(dp(28), 0, dp(28), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(26));
        if (principal) {
            g.setColor(acento);
            b.setTextColor(Color.WHITE);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(1), acento);
            b.setTextColor(texto);
        }
        b.setBackground(g);
        b.setStateListAnimator(null);
        return b;
    }

    private void mostrar(String texto, boolean conRueda) {
        vista.setVisibility(View.VISIBLE);
        vista.bringToFront();
        codigo.setVisibility(View.GONE);
        copiar.setVisibility(View.GONE);
        entrar.setText("Entrar con Google");
        ocupado(conRueda, texto);
    }

    private void mostrarCodigo(String c) {
        codigo.setText(codigoDe(c));
        codigo.setVisibility(View.VISIBLE);
        copiar.setVisibility(View.VISIBLE);
    }

    private void ocupado(boolean si, String texto) {
        ocupado = si;
        mensaje.setText(texto);
        rueda.setVisibility(si ? View.VISIBLE : View.GONE);
        entrar.setEnabled(!si);
        entrar.setAlpha(si ? 0.5f : 1f);
        if (si) {
            codigo.setVisibility(View.GONE);
            copiar.setVisibility(View.GONE);
        }
    }
}
