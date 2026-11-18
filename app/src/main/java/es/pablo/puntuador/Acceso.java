package es.pablo.puntuador;

import android.app.Activity;
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
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;

import java.util.Locale;

/**
 * Control de acceso con Firebase.
 *
 * - Se entra con la cuenta de Google (Credential Manager) y con ella se inicia sesión en Firebase Authentication.
 * - La lista de cuentas permitidas está en Firestore, colección «permitidos», un documento por persona
 *   cuyo ID es su correo en minúsculas. Es privada: las reglas de seguridad solo dejan a cada uno
 *   comprobar su propio documento, y nadie puede escribir en ella desde la app.
 * - Salida de emergencia: si el documento config/acceso tiene «abierto: true», la app deja entrar
 *   a cualquiera sin iniciar sesión.
 */
class Acceso {

    // Proyecto «Puntuador dianas» de Firebase / Google Cloud
    private static final String PROYECTO = "puntuador-dianas";
    private static final String APP_ID = "1:895728224312:android:fb40c534e2f2ebb0d0becd";
    private static final String CLAVE_API = "AIzaSyBSy5_qa9xKI7avpZ5IQrDCgvQNPb3vIGY";
    // Cliente web que creó Firebase al activar el inicio de sesión con Google
    private static final String CLIENTE_WEB = "895728224312-e1dctsuvk710jpeket43j6mjgn2ugcf8.apps.googleusercontent.com";

    private static final String PREFS = "acceso";
    private static final String K_CORREO = "correo";
    private static final String K_PERMITIDO = "permitido";

    private final Activity act;
    private final Runnable alPermitir;
    private final SharedPreferences prefs;
    private final CredentialManager credenciales;
    private final FirebaseAuth auth;
    private final FirebaseFirestore db;

    private ScrollView vista;
    private TextView mensaje;
    private Button entrar;
    private ProgressBar rueda;
    private boolean ocupado = false;

    Acceso(Activity act, Runnable alPermitir) {
        this.act = act;
        this.alPermitir = alPermitir;
        this.prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.credenciales = CredentialManager.create(act);
        if (FirebaseApp.getApps(act).isEmpty()) {
            FirebaseOptions o = new FirebaseOptions.Builder()
                    .setProjectId(PROYECTO)
                    .setApplicationId(APP_ID)
                    .setApiKey(CLAVE_API)
                    .build();
            FirebaseApp.initializeApp(act.getApplicationContext(), o);
        }
        this.auth = FirebaseAuth.getInstance();
        this.db = FirebaseFirestore.getInstance();
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
        FirebaseUser u = auth.getCurrentUser();
        boolean permitido = prefs.getBoolean(K_PERMITIDO, false);
        boolean sinCuenta = correo().isEmpty();
        if (permitido && (u != null || sinCuenta)) {
            // Ya tenía acceso: se abre al momento y se vuelve a comprobar por detrás
            vista.setVisibility(View.GONE);
            alPermitir.run();
            revisarEnSegundoPlano();
        } else {
            prefs.edit().clear().apply();
            mostrar("Para usar la app, entra con tu cuenta de Google.");
            // Si la app está abierta a todos, no hace falta iniciar sesión
            leerAbierto(abierto -> {
                if (Boolean.TRUE.equals(abierto) && !dejaVerLaApp() && !ocupado) conceder("");
            });
        }
    }

    /** Cerrar sesión desde Ajustes. */
    void cerrarSesion() {
        prefs.edit().clear().apply();
        auth.signOut();
        limpiarCredencial();
        mostrar("Has cerrado sesión. Entra con tu cuenta de Google para seguir usando la app.");
    }

    /* ---------------- Comprobaciones ---------------- */

    private interface Resultado<T> {
        void listo(T valor);
    }

    /** true / false si se ha podido leer config/acceso del servidor; null si no hay conexión. */
    private void leerAbierto(Resultado<Boolean> r) {
        db.collection("config").document("acceso").get(Source.SERVER)
                .addOnCompleteListener(act, t -> {
                    if (!t.isSuccessful()) {
                        r.listo(null);
                        return;
                    }
                    DocumentSnapshot d = t.getResult();
                    r.listo(d != null && Boolean.TRUE.equals(d.getBoolean("abierto")));
                });
    }

    /** true / false si se ha podido comprobar la lista en el servidor; null si no hay conexión. */
    private void leerPermitido(String c, Resultado<Boolean> r) {
        db.collection("permitidos").document(c).get(Source.SERVER)
                .addOnCompleteListener(act, t -> {
                    if (!t.isSuccessful()) {
                        r.listo(null);
                        return;
                    }
                    DocumentSnapshot d = t.getResult();
                    r.listo(d != null && d.exists());
                });
    }

    private void revisarEnSegundoPlano() {
        leerAbierto(abierto -> {
            if (abierto == null || abierto) return;   // sin conexión, o abierta a todos: se mantiene
            FirebaseUser u = auth.getCurrentUser();
            final String c = correo();
            if (u == null || c.isEmpty()) {
                retirar("Ahora hace falta entrar con tu cuenta de Google.");
                return;
            }
            leerPermitido(c, si -> {
                if (si == null || si) return;
                retirar("La cuenta " + c + " ya no tiene acceso a la app.");
            });
        });
    }

    private void retirar(String texto) {
        prefs.edit().clear().apply();
        auth.signOut();
        limpiarCredencial();
        mostrar(texto);
    }

    /* ---------------- Inicio de sesión ---------------- */

    private void iniciarSesion() {
        if (ocupado) return;
        ocupado(true, "Abriendo el inicio de sesión de Google…");
        GetSignInWithGoogleOption opcion = new GetSignInWithGoogleOption.Builder(CLIENTE_WEB).build();
        GetCredentialRequest peticion = new GetCredentialRequest.Builder().addCredentialOption(opcion).build();
        credenciales.getCredentialAsync(act, peticion, new CancellationSignal(), act.getMainExecutor(),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse r) {
                        String token = tokenDe(r.getCredential());
                        if (token == null) {
                            ocupado(false, "Google no ha devuelto una cuenta válida. Vuelve a probar.");
                            return;
                        }
                        entrarEnFirebase(token);
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

    private static String tokenDe(Credential cr) {
        try {
            if (cr instanceof CustomCredential
                    && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(cr.getType())) {
                return GoogleIdTokenCredential.createFrom(cr.getData()).getIdToken();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void entrarEnFirebase(String token) {
        ocupado(true, "Entrando…");
        auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                .addOnCompleteListener(act, t -> {
                    FirebaseUser u = auth.getCurrentUser();
                    if (!t.isSuccessful() || u == null || u.getEmail() == null) {
                        Exception e = t.getException();
                        ocupado(false, "No se ha podido iniciar sesión. Revisa la conexión y vuelve a probar."
                                + (e != null && e.getMessage() != null ? "\n\nDetalle: " + e.getMessage() : ""));
                        return;
                    }
                    comprobar(u.getEmail().trim().toLowerCase(Locale.ROOT));
                });
    }

    private void comprobar(final String c) {
        ocupado(true, "Comprobando si " + c + " tiene acceso…");
        leerPermitido(c, si -> {
            if (si == null) {
                ocupado(false, "No se ha podido comprobar el acceso. Revisa la conexión y vuelve a probar.");
                auth.signOut();
                return;
            }
            if (si) {
                conceder(c);
                return;
            }
            leerAbierto(abierto -> {
                if (Boolean.TRUE.equals(abierto)) {
                    conceder(c);
                    return;
                }
                auth.signOut();
                limpiarCredencial();
                ocupado(false, "La cuenta " + c + " no tiene acceso a la app todavía.\n\nPide a Pablo que te dé acceso y vuelve a entrar.");
                entrar.setText("Entrar con otra cuenta");
            });
        });
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
        mensaje.setTextIsSelectable(true);
        LinearLayout.LayoutParams lpM = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpM.topMargin = dp(16);
        col.addView(mensaje, lpM);

        rueda = new ProgressBar(act);
        rueda.setVisibility(View.GONE);
        LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(dp(36), dp(36));
        lpR.topMargin = dp(24);
        col.addView(rueda, lpR);

        entrar = new Button(act);
        entrar.setText("Entrar con Google");
        entrar.setAllCaps(false);
        entrar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        entrar.setPadding(dp(28), 0, dp(28), 0);
        entrar.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(26));
        g.setColor(acento);
        entrar.setBackground(g);
        entrar.setStateListAnimator(null);
        entrar.setOnClickListener(v -> iniciarSesion());
        LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        lpB.topMargin = dp(28);
        col.addView(entrar, lpB);

        raiz.addView(vista, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void mostrar(String texto) {
        vista.setVisibility(View.VISIBLE);
        vista.bringToFront();
        entrar.setText("Entrar con Google");
        ocupado(false, texto);
    }

    private void ocupado(boolean si, String texto) {
        ocupado = si;
        mensaje.setText(texto);
        rueda.setVisibility(si ? View.VISIBLE : View.GONE);
        entrar.setEnabled(!si);
        entrar.setAlpha(si ? 0.5f : 1f);
    }
}
