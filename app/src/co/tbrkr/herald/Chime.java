package co.tbrkr.herald;

/**
 * The chime before each announcement: mono 16-bit, 659.25 Hz for 0.32 s then 880 Hz for
 * 0.75 s, each note min(1, t/0.012) * exp(-5.5 t) * (sin(2 pi f t) + 0.18 sin(4 pi f t)),
 * scaled so the peak is a quarter of full scale. Pure Java: no Android classes.
 * PLAN-20261125-herald-v1.
 */
final class Chime {
    static final double PEAK = 0.25;

    private Chime() {}

    static short[] pcm(int rate) {
        int first = (int) (rate * 0.32);
        int second = (int) (rate * 0.75);
        double[] raw = new double[first + second];
        note(raw, 0, first, 659.25, rate);
        note(raw, first, second, 880.0, rate);
        double peak = 0;
        for (double v : raw) peak = Math.max(peak, Math.abs(v));
        double scale = peak > 0 ? PEAK * 32767 / peak : 0;
        short[] out = new short[raw.length];
        for (int i = 0; i < raw.length; i++) out[i] = (short) Math.round(raw[i] * scale);
        return out;
    }

    private static void note(double[] raw, int from, int count, double f, int rate) {
        for (int k = 0; k < count; k++) {
            double t = (double) k / rate;
            raw[from + k] = Math.min(1, t / 0.012) * Math.exp(-5.5 * t)
                    * (Math.sin(2 * Math.PI * f * t) + 0.18 * Math.sin(4 * Math.PI * f * t));
        }
    }
}
