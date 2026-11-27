package io.github.iwinties.herald;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;

import static io.github.iwinties.herald.PairingText.px;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Owns one pairing overlay. Its looper never calls Auth, so UI and HTTP starts can share the Auth lock. */
final class PairingWindow {
    private final Context context;
    private final WindowManager windows;
    private final HandlerThread thread = new HandlerThread("herald-pairing-window");
    private final Handler handler;
    private Panel view;
    private static final int BLUE = 0xff3ba3e0;
    private final Runnable clear = this::remove;

    PairingWindow(Context context) {
        if (Build.VERSION.SDK_INT >= 30) {
            DisplayManager displays = context.getSystemService(DisplayManager.class);
            Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
            context = context.createDisplayContext(display).createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        this.context = context;
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    private static GradientDrawable shape(int color, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(radius);
        return result;
    }

    private static void place(FrameLayout parent, View child, int x, int y, int w, int h) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.LEFT);
        params.leftMargin = x;
        params.topMargin = y;
        parent.addView(child, params);
    }

    private final class Panel extends FrameLayout implements Runnable {
        final FrameLayout body;
        final int sh;
        final int width;
        final String deviceName;
        final int seconds;
        TextView footer;
        View progress;
        long end;

        Panel(PairingText.Layout layout, int sh, String client, String code, String deviceName, int seconds) {
            super(context);
            this.sh = sh;
            this.seconds = seconds;
            this.deviceName = deviceName;
            int[] bounds = layout.body, window = layout.window;
            width = bounds[2];
            int height = bounds[3];
            int x = bounds[0] - window[0], y = bounds[1] - window[1];
            int spread = px(sh, 16), radius = px(sh, 30);
            View shadow = new View(context);
            shadow.setBackground(shape(0x8c000000, radius + spread));
            place(this, shadow, x - spread, y - spread + px(sh, 4),
                    width + 2 * spread, height + 2 * spread);
            body = new FrameLayout(context);
            GradientDrawable background = shape(0xdb161a22, radius);
            background.setStroke(1, 0xff3a404c);
            body.setBackground(background);
            place(this, body, x, y, width, height);

            boolean paired = code == null;
            int badgeSize = px(sh, 40);
            TextView badge = label(paired ? "✓" : "H", 28, Color.WHITE, Typeface.DEFAULT_BOLD);
            GradientDrawable badgeShape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{BLUE, paired ? BLUE : 0xff7b5cff});
            badgeShape.setCornerRadius(px(sh, 10));
            badge.setBackground(badgeShape);
            place(body, badge, (width - badgeSize + 1) / 2, layout.badgeY, badgeSize, badgeSize);
            line(label((paired ? "Paired with " : "Pair with ") + client, 30, Color.WHITE,
                    Typeface.DEFAULT_BOLD), layout.titleY, 45);
            if (paired) return;
            line(label("Enter this code in " + client, 18, 0xbfffffff, Typeface.DEFAULT), layout.subtitleY, 30);
            String groups = PairingText.groups(code);
            for (int i = 0; i < layout.boxes.length; i++) {
                int[] box = layout.boxes[i];
                int index = i < 3 ? i : i + 1;
                TextView digit = label(groups.substring(index, index + 1), 50, Color.WHITE,
                        Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                GradientDrawable tile = shape(0x1affffff, px(sh, 6));
                tile.setStroke(1, 0x33ffffff);
                digit.setBackground(tile);
                place(body, digit, box[0] - bounds[0], box[1] - bounds[1], box[2], box[3]);
            }
            View track = new View(context);
            track.setBackgroundColor(0x1fffffff);
            line(track, layout.progressY, 4);
            progress = new View(context);
            progress.setBackgroundColor(BLUE);
            progress.setPivotX(0);
            line(progress, layout.progressY, 4);
            footer = label("", 14, 0x99ffffff, Typeface.DEFAULT);
            line(footer, layout.footerY, 25);
        }

        private void line(View view, int y, int height) {
            int padding = px(sh, 30);
            place(body, view, padding, y, width - 2 * padding, px(sh, height));
        }

        private TextView label(String value, int fontSize, int color, Typeface face) {
            TextView text = new TextView(context);
            text.setGravity(Gravity.CENTER);
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setIncludeFontPadding(false);
            text.setTypeface(face);
            text.setTextColor(color);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sh, fontSize));
            text.setText(value);
            return text;
        }

        @Override public void run() {
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0 || footer == null) return;
            int remaining = (int) ((left + 999) / 1000);
            footer.setText("Expires in " + PairingText.remaining(remaining) + " · " + deviceName);
            progress.setScaleX((float) left / (seconds * 1000L));
            handler.postAtTime(this, end - (remaining - 1) * 1000L);
        }
    }

    boolean showCode(String client, String code, String deviceName, int seconds) {
        return show(client, code, deviceName, seconds);
    }

    void showPaired(String client) { show(client, null, null, 5); }

    private boolean show(String client, String code, String deviceName, int seconds) {
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            remove();
            Point size = new Point();
            windows.getDefaultDisplay().getRealSize(size);
            PairingText.Layout layout = PairingText.layout(size.x, size.y);
            int[] window = layout.window;
            Panel panel = new Panel(layout, size.y, client, code, deviceName, seconds);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    window[2] - window[0], window[3] - window[1],
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = window[0];
            params.y = window[1];
            if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
            try {
                windows.addView(panel, params);
            } catch (RuntimeException e) {
                return false;
            }
            view = panel;
            panel.end = SystemClock.uptimeMillis() + seconds * 1000L;
            panel.run();
            handler.postAtTime(clear, panel.end);
            return true;
        });
        if (!handler.post(task)) return false;
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            hide();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    void hide() { handler.post(this::remove); }

    private void remove() {
        handler.removeCallbacks(clear);
        if (view != null) {
            handler.removeCallbacks(view);
            windows.removeViewImmediate(view);
            view = null;
        }
    }

    void close() {
        hide();
        thread.quitSafely();
    }
}
