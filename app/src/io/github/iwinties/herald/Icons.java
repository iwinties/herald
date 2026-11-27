package io.github.iwinties.herald;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Built-in vector icons in a shared 24-unit viewport. IconViews handles other icon types. */
final class Icons extends View {
    private final Path path = new Path();
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);

    Icons(Context context, String icon, int color) {
        super(context);
        pen.setColor(color);
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeWidth(1.8f);
        pen.setStrokeCap(Paint.Cap.ROUND);
        pen.setStrokeJoin(Paint.Join.ROUND);
        switch (icon) {
            case "info":
                circle(12,12,9); line(12,11,12,17); circle(12,7,0.5f); break;
            case "bell":
                line(4,17,6,14,6,10); path.cubicTo(6,1,18,1,18,10); line(18,10,18,14,20,17,4,17);
                path.moveTo(9,20); path.quadTo(12,23,15,20); break;
            case "door":
                line(4,21,4,3,18,3,18,21,4,21); line(7,20,7,5,15,7,15,20); circle(12,13,0.5f); break;
            case "camera":
                line(2,8,7,8,9,5,15,5,17,8,22,8,22,20,2,20,2,8); circle(12,13,4); break;
            case "alarm":
                circle(12,13,8); line(12,8,12,13,15,15); line(2,6,6,2); line(18,2,22,6);
                line(6,19,4,22); line(18,19,20,22); break;
            case "warning":
                line(12,2,23,21,1,21,12,2); line(12,9,12,14); circle(12,17.5f,.5f); break;
            case "home":
                line(2,11,12,3,22,11); line(5,10,5,21,10,21,10,15,14,15,14,21,19,21,19,10); break;
            case "media": circle(12,12,10); line(10,7,17,12,10,17,10,7); break;
            case "megaphone":
                line(2,10,8,10,20,4,20,20,8,15,2,15,2,10); line(6,15,8,21,11,21,9,16); break;
            case "person":
                circle(12,7,4); path.moveTo(3,21); path.cubicTo(3,10,21,10,21,21); path.close(); break;
            case "package":
                line(2,7,12,2,22,7,22,17,12,22,2,17,2,7,12,12,22,7);
                line(12,12,12,22); line(7,5,17,10); break;
            case "tv":
                path.addRoundRect(2,4,22,18,2,2,Path.Direction.CW); line(12,18,12,21); line(7,21,17,21); break;
            case "timer": circle(12,14,8); line(9,2,15,2); line(12,2,12,6); line(12,14,16,10); break;
            case "check": line(3,12,9,18,21,5); break;
            default: break;
        }
    }

    private void circle(float x, float y, float r) { path.addCircle(x,y,r,Path.Direction.CW); }
    private void line(int... xy) {
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i+1]);
    }
    @Override protected void onDraw(Canvas c) {
        c.save();
        c.scale(getWidth()/24f, getHeight()/24f);
        c.drawPath(path, pen);
        c.restore();
    }
}
