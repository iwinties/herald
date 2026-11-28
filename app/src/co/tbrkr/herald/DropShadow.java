package co.tbrkr.herald;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
        prepare(width,height,radius);
        canvas.drawBitmap(mask, left - reach, top - reach + offset, blit);
    }

    /** Stretch only the flat runs of a small blur mask while the video card changes size. */
    void drawMoving(Canvas canvas, int left, int top, int width, int height, float radius) {
        if (reach == 0 || width <= 0 || height <= 0) return;
        int cap = 2*reach + (int)Math.ceil(radius);
        int source = 2*cap + 1;
        prepare(source,source,radius);
        int border = cap+reach;
        int outerWidth = width+2*reach, outerHeight = height+2*reach;
        int bx = Math.min(border,outerWidth/2), by = Math.min(border,outerHeight/2);
        int[] cuts = {0,border,border+1,mask.getWidth()};
        int[] xs = {0,bx,outerWidth-bx,outerWidth}, ys = {0,by,outerHeight-by,outerHeight};
        int x = left-reach, y = top-reach+offset;
        for (int row=0; row<3; row++) for (int col=0; col<3; col++) {
            if (row == 1 && col == 1) continue;
            canvas.drawBitmap(mask, new Rect(cuts[col],cuts[row],cuts[col+1],cuts[row+1]),
                    new Rect(x+xs[col],y+ys[row],x+xs[col+1],y+ys[row+1]),blit);
        }
    }

    private void prepare(int width, int height, float radius) {
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
    }
}
