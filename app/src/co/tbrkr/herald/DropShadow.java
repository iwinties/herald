package co.tbrkr.herald;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

/** A cached alpha mask so software and hardware overlay canvases use the same blur. */
final class DropShadow {
    final int reach, offset;
    private final boolean strong;
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap mask;
    private float lastRadius;

    DropShadow(String style, int screenHeight) {
        strong = "strong".equals(style);
        reach = Math.round(screenHeight * (strong ? .040f : "soft".equals(style) ? .024f : 0f));
        offset = reach == 0 ? 0 : Math.round(screenHeight * (strong ? .011f : .006f));
    }

    void draw(Canvas canvas, int left, int top, int width, int height, float radius) {
        if (reach == 0 || width <= 0 || height <= 0) return;
        int w = width + 2 * reach, h = height + 2 * reach;
        if (mask == null || mask.getWidth() != w || mask.getHeight() != h || lastRadius != radius) {
            mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            mask.setDensity(Bitmap.DENSITY_NONE);
            Canvas buffer = new Canvas(mask);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            // A broad lower shadow gives depth. A tighter contact shadow grounds the edge.
            paint.setColor(strong ? 0x68000000 : 0x48000000);
            paint.setMaskFilter(new BlurMaskFilter(reach * .52f, BlurMaskFilter.Blur.NORMAL));
            buffer.drawRoundRect(reach, reach, reach + width, reach + height, radius, radius, paint);
            int faceTop = reach - offset;
            float contactTop = faceTop + offset * .30f;
            paint.setColor(strong ? 0x46000000 : 0x34000000);
            paint.setMaskFilter(new BlurMaskFilter(reach * .17f, BlurMaskFilter.Blur.NORMAL));
            buffer.drawRoundRect(reach, contactTop, reach + width, contactTop + height, radius, radius, paint);
            // Translucent surfaces must still show the scene at their configured opacity.
            paint.setMaskFilter(null);
            paint.setColor(Color.BLACK);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            buffer.drawRoundRect(reach, faceTop, reach + width, faceTop + height, radius, radius, paint);
            lastRadius = radius;
        }
        canvas.drawBitmap(mask, left - reach, top - reach + offset, blit);
    }
}
