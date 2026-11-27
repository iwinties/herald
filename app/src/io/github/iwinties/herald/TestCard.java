package io.github.iwinties.herald;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;

/** An activity-owned preview, available before the receiver has ever started. */
final class TestCard {
    private final WindowManager windows;
    private final CardView card;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::dismiss;

    static TestCard show(Context context) { return new TestCard(context); }

    private TestCard(Context context) {
        windows = context.getSystemService(WindowManager.class);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        Settings defaults = new Settings("Herald", null, null, null);
        CardSpec spec = new CardSpec(Json.fields("title", "Herald test", "message", "Cards will appear like this."), defaults);
        card = new CardView(context, new CardBoard.Entry(spec, 0, 0, 0), metrics.widthPixels,
                metrics.heightPixels, "General", null, () -> {});
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(card.width,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.RIGHT;
        windows.addView(card, params);
        main.postDelayed(timeout, 8000);
    }

    boolean dismiss() {
        main.removeCallbacks(timeout);
        if (!card.isAttachedToWindow()) return false;
        windows.removeViewImmediate(card);
        return true;
    }
}
