package es.pablo.puntuador;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Conexión con el lector de impactos: el ESP32 con cámara (en 192.168.4.1) o la app «Lector de dianas»
 * en un móvil viejo con su zona WiFi activada (en la puerta de enlace de esa red, puerto 8080).
 * El lector crea su propia red WiFi sin internet. La app se une a ella solo para hablar con el lector
 * (el resto del móvil sigue usando sus datos), pregunta cada poco por /estado y, cuando hay un aviso
 * nuevo, descarga su foto y se la pasa a la página.
 * Si el móvil tiene una VPN activa, Android no deja a la app elegir la red WiFi: entonces se prueba
 * por el camino normal del móvil (funciona si la VPN deja pasar la red local) y, si no, se avisa.
 */
final class Lector {

    interface Oyente {
        void estado(JSONObject o);
        void evento(JSONObject o);
    }

    static final String RED = "LineaDeTiro-Lector";
    static final String CLAVE = "lineadetiro";
    static final String BASE = "http://192.168.4.1";

    private final ConnectivityManager cm;
    private final Oyente oyente;

    private volatile boolean activo = false;
    private volatile Network red = null;
    private volatile String base = null;
    /** Con VPN: las peticiones van por el camino normal del móvil, sin atarlas a la red WiFi. */
    private volatile boolean directo = false;
    /** Android ha negado usar la red WiFi a causa de una VPN. */
    private volatile boolean vpnBloquea = false;
    private volatile boolean pedida = false;
    private volatile boolean rechazada = false;
    private ConnectivityManager.NetworkCallback cb = null;
    private Thread hilo = null;
    private long seqVisto = -1;

    Lector(Context ctx, Oyente oyente) {
        this.cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.oyente = oyente;
    }

    boolean activo() {
        return activo;
    }

    synchronized void conectar() {
        rechazada = false;
        vpnBloquea = false;
        if (activo) {
            if (red == null && !pedida) pedirRed();
            return;
        }
        activo = true;
        seqVisto = -1;
        hilo = new Thread(this::bucle, "lector");
        hilo.start();
    }

    synchronized void desconectar() {
        activo = false;
        if (hilo != null) hilo.interrupt();
        hilo = null;
        soltarRed();
        red = null;
        base = null;
        directo = false;
        avisar("desconectado", "Lector desconectado.", null);
    }

    /** La imagen actual pasa a ser la diana nueva. */
    void referencia() {
        enSegundoPlano(() -> pedirTexto("/referencia"));
    }

    void ajustes(final String consulta) {
        enSegundoPlano(() -> {
            String r = pedirTexto("/ajustes?" + consulta);
            if (r != null) {
                try {
                    avisar("conectado", null, new JSONObject(r));
                } catch (Exception ignored) {
                }
            }
        });
    }

    /* ---------------- Bucle de vigilancia ---------------- */

    private void bucle() {
        int fallos = 0;
        long ultimoIntento = 0;
        avisar("buscando", "Buscando el lector…", null);
        while (activo) {
            try {
                if (red != null && base == null) {
                    base = buscarBase(red);
                    if (base == null) {
                        avisar("buscando", vpnBloquea || hayVpn()
                                ? TEXTO_VPN
                                : "Conectado a la red " + RED + ", pero no encuentro el lector en ella. ¿Está abierta la app Lector o encendido el ESP32?", null);
                        dormir(1500);
                        continue;
                    }
                }
                if (red == null) {
                    Network n = buscarRedConLector();
                    if (n != null) {
                        red = n;
                    } else {
                        long ahora = System.currentTimeMillis();
                        if (!pedida && !rechazada && ahora - ultimoIntento > 15000) {
                            ultimoIntento = ahora;
                            pedirRed();
                        }
                        avisar("buscando", textoSinLector(), null);
                        dormir(1500);
                        continue;
                    }
                }

                String txt = pedirTexto("/estado");
                if (!esLector(txt)) {
                    if (++fallos >= 4) {
                        avisar("buscando", "He perdido el lector. Comprueba que está encendido y cerca.", null);
                        if (fallos >= 8) {
                            red = null;
                            base = null;
                            directo = false;
                        }
                    }
                    dormir(700);
                    continue;
                }
                fallos = 0;
                JSONObject e = new JSONObject(txt);
                long seq = e.optLong("seq", 0);
                if (seqVisto < 0) {
                    // Primera vez: la foto actual sirve de punto de partida
                    avisar("conectado", null, e);
                    byte[] foto = pedirBytes("/foto");
                    if (foto != null) {
                        seqVisto = seq;
                        enviarEvento("conexion", e, foto);
                    }
                } else if (seq != seqVisto) {
                    byte[] foto = pedirBytes("/foto?seq=" + seq);
                    if (foto != null) {
                        seqVisto = seq;
                        enviarEvento(e.optString("tipo", "impacto"), e, foto);
                    }
                }
                dormir(350);
            } catch (Exception ex) {
                dormir(800);
            }
        }
    }

    private void enviarEvento(String tipo, JSONObject e, byte[] foto) {
        try {
            JSONObject o = new JSONObject();
            o.put("tipo", tipo);
            o.put("seq", e.optLong("seq", 0));
            o.put("x", e.optDouble("x", 0));
            o.put("y", e.optDouble("y", 0));
            o.put("pix", e.optInt("pix", 0));
            o.put("manual", e.optBoolean("manual", false));
            o.put("lector", e);
            o.put("foto", "data:image/jpeg;base64," + Base64.encodeToString(foto, Base64.NO_WRAP));
            oyente.evento(o);
        } catch (Exception ignored) {
        }
    }

    private static final String TEXTO_VPN = "No llego al lector porque este móvil tiene una VPN activa. "
            + "Desactívala (o excluye Puntuador en la VPN), comprueba que sigues en la WiFi del lector y pulsa «Conectar».";

    /** Explica por qué no aparece el lector, con lo que se sabe de las redes del móvil. */
    private String textoSinLector() {
        if (vpnBloquea || hayVpn()) return TEXTO_VPN;
        List<String> puertas = puertasWifi();
        if (!puertas.isEmpty()) {
            String p = puertas.get(0);
            return "Este móvil está en una WiFi (puerta de enlace " + p + "), pero el lector no responde en ella. "
                    + "Comprueba que es la zona WiFi del móvil lector, que la app Lector está abierta y que la dirección que muestra es "
                    + p + ". Si Android avisa de que esa WiFi no tiene internet, elige mantener la conexión.";
        }
        return rechazada
                ? "No me he podido unir a la red " + RED + ". Enciende el lector y pulsa «Conectar» otra vez, o únete a esa red desde los ajustes de WiFi."
                : "Buscando el lector… Si Android pregunta, acepta unirte a la red " + RED + ".";
    }

    /** La respuesta de /estado es la del lector (y no, por ejemplo, la página de un router). */
    private static boolean esLector(String txt) {
        return txt != null && txt.trim().startsWith("{") && txt.contains("\"seq\"");
    }

    private void avisar(String estado, String texto, JSONObject info) {
        try {
            JSONObject o = new JSONObject();
            o.put("estado", estado);
            if (texto != null) o.put("texto", texto);
            if (info != null) o.put("info", info);
            oyente.estado(o);
        } catch (Exception ignored) {
        }
    }

    /* ---------------- Red WiFi del lector ---------------- */

    /** Busca entre las redes WiFi activas una desde la que responda el lector (también si el usuario se unió a mano). */
    private Network buscarRedConLector() {
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null || !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;   // una VPN sobre WiFi no es la red del lector
                String b = buscarBase(n);
                if (b != null) {
                    base = b;
                    return n;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Dirección del lector dentro de una red: el ESP32 está en 192.168.4.1; el móvil con zona WiFi es la puerta de enlace. */
    private String buscarBase(Network n) {
        boolean vpn = hayVpn();
        for (String b : candidatos(n)) {
            if (esLector(pedirTexto(n, b + "/estado", 1500))) {
                directo = false;
                return b;
            }
            // Con VPN, Android puede negar el uso directo de la WiFi: se prueba por el camino normal
            if ((vpn || vpnBloquea) && esLector(pedirTexto(null, b + "/estado", 1500))) {
                directo = true;
                return b;
            }
        }
        return null;
    }

    private boolean hayVpn() {
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Puertas de enlace de las redes WiFi a las que está unido el móvil (para explicar dónde se ha buscado). */
    private List<String> puertasWifi() {
        List<String> r = new ArrayList<>();
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null || !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (RouteInfo ri : lp.getRoutes()) {
                    InetAddress g = ri.getGateway();
                    if (g instanceof Inet4Address && !g.isAnyLocalAddress() && !r.contains(g.getHostAddress())) r.add(g.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    private List<String> candidatos(Network n) {
        List<String> r = new ArrayList<>();
        r.add(BASE);
        try {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) {
                for (RouteInfo ri : lp.getRoutes()) {
                    InetAddress g = ri.getGateway();
                    if (g instanceof Inet4Address && !g.isAnyLocalAddress()) {
                        String ip = g.getHostAddress();
                        if (!r.contains("http://" + ip + ":8080")) {
                            r.add("http://" + ip + ":8080");
                            r.add("http://" + ip);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    @TargetApi(29)
    private synchronized void pedirRed() {
        if (Build.VERSION.SDK_INT < 29 || pedida) return;
        try {
            WifiNetworkSpecifier esp = new WifiNetworkSpecifier.Builder()
                    .setSsid(RED)
                    .setWpa2Passphrase(CLAVE)
                    .build();
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(esp)
                    .build();
            cb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    red = n;
                }

                @Override
                public void onLost(Network n) {
                    if (n.equals(red)) red = null;
                }

                @Override
                public void onUnavailable() {
                    pedida = false;
                    rechazada = true;
                    cb = null;
                }
            };
            pedida = true;
            cm.requestNetwork(req, cb);
        } catch (Exception ex) {
            pedida = false;
            rechazada = true;
        }
    }

    private synchronized void soltarRed() {
        if (cb != null) {
            try {
                cm.unregisterNetworkCallback(cb);
            } catch (Exception ignored) {
            }
        }
        cb = null;
        pedida = false;
    }

    /* ---------------- Peticiones HTTP por la red del lector ---------------- */

    private String pedirTexto(String ruta) {
        Network n = red;
        String b = base;
        if (n == null || b == null) return null;
        return pedirTexto(directo ? null : n, b + ruta, 2500);
    }

    private String pedirTexto(Network n, String url, int espera) {
        byte[] b = pedir(n, url, espera, 4000);
        return b == null ? null : new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] pedirBytes(String ruta) {
        Network n = red;
        String b = base;
        if (n == null || b == null) return null;
        return pedir(directo ? null : n, b + ruta, 3000, 15000);
    }

    /** Con n == null la petición no se ata a ninguna red: sale por donde el móvil decida. */
    private byte[] pedir(Network n, String url, int espera, int lectura) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            c = (HttpURLConnection) (n != null ? n.openConnection(u) : u.openConnection(Proxy.NO_PROXY));
            c.setConnectTimeout(espera);
            c.setReadTimeout(lectura);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                byte[] buf = new byte[16384];
                int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                return out.toByteArray();
            }
        } catch (Exception e) {
            // Con una VPN que no se puede esquivar, Android niega atar la conexión a la WiFi (EPERM)
            if (n != null) {
                for (Throwable t = e; t != null; t = t.getCause()) {
                    if (String.valueOf(t.getMessage()).contains("EPERM")) {
                        vpnBloquea = true;
                        break;
                    }
                }
            }
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void enSegundoPlano(Runnable r) {
        new Thread(r, "lector-orden").start();
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
