package co.tbrkr.herald;

/** Presentation geometry in screen pixels. Logical card state stays in CardBoard. */
final class CardMotion {
    static final class Frame {
        final float x, y, width, height, opacity, full;
        Frame(float x, float y, float width, float height, float opacity, float full) {
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.opacity = opacity; this.full = full;
        }
        Frame transformed(float dx, float dy, float scale, float alpha) {
            return new Frame(x + dx + width * (1-scale)/2, y + dy + height * (1-scale)/2,
                    width * scale, height * scale, alpha, full);
        }
        Frame toward(Frame target, float t) {
            return new Frame(mix(x,target.x,t), mix(y,target.y,t), mix(width,target.width,t),
                    mix(height,target.height,t), mix(opacity,target.opacity,t), mix(full,target.full,t));
        }
        boolean sameGeometry(Frame b) {
            return b != null && x == b.x && y == b.y && width == b.width && height == b.height && full == b.full;
        }
    }
    static float mix(float a, float b, float t) { return a + (b-a)*t; }

    static Frame edge(Frame f, String preset, boolean entering, boolean bottom) {
        String id = preset.replace("_fade", "");
        if (id.equals("slide_auto")) id = bottom ? "slide_bottom" : "slide_top";
        float dx = 0, dy = 0, scale = 1;
        switch (id) {
            case "slide_left": dx = -f.width; break;
            case "slide_right": dx = f.width; break;
            case "slide_top": dy = -f.height; break;
            case "slide_bottom": dy = f.height; break;
            case "grow": scale = entering ? .92f : 1.08f; break;
            case "shrink": scale = entering ? 1.08f : .92f; break;
        }
        return f.transformed(dx, dy, scale, preset.contains("fade") ? 0 : f.opacity);
    }

    static Frame resize(Frame from, Frame to, String preset, float t) {
        if ("fade".equals(preset)) {
            Frame base = t < .5f ? from : to;
            return base.transformed(0,0,1, base.opacity * Math.abs(2*t-1));
        }
        Frame current = from.toward(to,t);
        return "morph_fade".equals(preset) ? current.transformed(0,0,1,
                current.opacity * (1 - .1f*(float)Math.sin(Math.PI*t))) : current;
    }
    static double tickerEmPerSecond(String speed) {
        switch (speed) {
            case "slow": return 1.2;
            case "normal": return 1.8;
            case "fast": return 2.7;
            default: throw new IllegalArgumentException("Unknown ticker speed");
        }
    }

    static float tickerOffset(float length, float viewport, float em, long elapsed, double emPerSecond) {
        if (length <= viewport || elapsed <= 900 || em <= 0) return 0;
        return (float)(((elapsed-900)*emPerSecond*em/1000d) % (length+3*em));
    }
    private CardMotion() {}
}
