package es.pablo.lectordianas;

import java.nio.ByteBuffer;

/**
 * Vigila la diana con imágenes pequeñas en gris (unos 200 píxeles de ancho), igual que el firmware del ESP32:
 * compara cada imagen con una referencia corrigiendo el brillo global. Si cambian pocos píxeles y la imagen
 * se queda quieta dos veces seguidas, es un impacto; si cambian muchos, es una diana nueva («cambio»).
 */
final class Vigilante {

    interface Aviso {
        /** x, y: posición aproximada (0..1) en la imagen de vigilancia, sin girar. */
        void aviso(String tipo, float x, float y, int pix, boolean manual);
    }

    private final Aviso aviso;
    private int dw, dh, factor;
    private byte[] cur, ref, prev;
    private boolean refOk = false;
    private int calentando = 0;
    private boolean cambiando = false;
    private int quietos = 0;
    private volatile boolean pedirRef = false;
    private volatile int sens = 50;
    private volatile boolean pausado = false;

    Vigilante(Aviso aviso) {
        this.aviso = aviso;
    }

    void setSens(int s) {
        sens = Math.max(0, Math.min(100, s));
    }

    int getSens() {
        return sens;
    }

    /** Toma la imagen actual como diana nueva. */
    void referencia() {
        pedirRef = true;
    }

    /** Sin vigilar (por ejemplo mientras se enfoca o cambia el zoom); al volver empieza de cero. */
    void pausar(boolean p) {
        pausado = p;
        if (p) {
            refOk = false;
            calentando = 0;
            cambiando = false;
        }
    }

    boolean vigilando() {
        return refOk && !pausado;
    }

    private int umbral() {
        return 40 - sens / 4;
    }

    private int minPix() {
        return 3 + (100 - sens) / 20;
    }

    private int maxImpacto() {
        return dw * dh / 80;
    }

    /** Plano Y (luminancia) de la imagen de análisis. */
    void procesar(ByteBuffer y, int w, int h, int rowStride, int pixelStride) {
        if (pausado) return;
        int f = Math.max(1, Math.round(w / 200f));
        int nw = w / f, nh = h / f;
        if (nw != dw || nh != dh || f != factor || cur == null) {
            dw = nw;
            dh = nh;
            factor = f;
            cur = new byte[dw * dh];
            ref = new byte[dw * dh];
            prev = new byte[dw * dh];
            refOk = false;
            calentando = 0;
            cambiando = false;
        }
        // Reducción por bloques f×f
        final int area = f * f;
        int[] fila = new int[dw];
        for (int by = 0; by < dh; by++) {
            java.util.Arrays.fill(fila, 0);
            for (int yy = 0; yy < f; yy++) {
                int base = (by * f + yy) * rowStride;
                for (int bx = 0; bx < dw; bx++) {
                    int p = base + bx * f * pixelStride, s = 0;
                    for (int xx = 0; xx < f; xx++) s += y.get(p + xx * pixelStride) & 0xFF;
                    fila[bx] += s;
                }
            }
            for (int bx = 0; bx < dw; bx++) cur[by * dw + bx] = (byte) (fila[bx] / area);
        }
        vigilar();
    }

    private void vigilar() {
        final int n = dw * dh;
        if (!refOk) {
            System.arraycopy(cur, 0, prev, 0, n);
            if (++calentando >= 6) {
                System.arraycopy(cur, 0, ref, 0, n);
                refOk = true;
                aviso.aviso("inicio", 0, 0, 0, false);
            }
            return;
        }
        if (pedirRef) {
            pedirRef = false;
            System.arraycopy(cur, 0, ref, 0, n);
            System.arraycopy(cur, 0, prev, 0, n);
            cambiando = false;
            aviso.aviso("cambio", 0, 0, 0, true);
            return;
        }
        long sR = 0, sP = 0;
        for (int i = 0; i < n; i++) {
            int c = cur[i] & 0xFF;
            sR += c - (ref[i] & 0xFF);
            sP += c - (prev[i] & 0xFF);
        }
        final int offR = (int) (sR / n), offP = (int) (sP / n), T = umbral();
        int cambiados = 0, mov = 0;
        long sx = 0, sy = 0;
        for (int yy = 2; yy < dh - 2; yy++) {
            int filaI = yy * dw;
            for (int xx = 2; xx < dw - 2; xx++) {
                int i = filaI + xx, c = cur[i] & 0xFF;
                if (Math.abs(c - (ref[i] & 0xFF) - offR) > T) {
                    cambiados++;
                    sx += xx;
                    sy += yy;
                }
                if (Math.abs(c - (prev[i] & 0xFF) - offP) > T) mov++;
            }
        }
        final int mp = minPix();
        if (!cambiando) {
            if (cambiados >= mp) {
                cambiando = true;
                quietos = 0;
            } else {
                for (int i = 0; i < n; i++) ref[i] = (byte) ((((ref[i] & 0xFF) * 15) + (cur[i] & 0xFF) + 8) >> 4);
            }
        } else {
            quietos = mov < mp ? quietos + 1 : 0;
            if (quietos >= 2) {
                if (cambiados >= mp) {
                    float fx = (float) sx / cambiados / dw, fy = (float) sy / cambiados / dh;
                    aviso.aviso(cambiados <= maxImpacto() ? "impacto" : "cambio", fx, fy, cambiados, false);
                }
                System.arraycopy(cur, 0, ref, 0, n);
                cambiando = false;
            }
        }
        System.arraycopy(cur, 0, prev, 0, n);
    }
}
