package io.github.iwinties.herald;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.IOException;

final class IconViews {
    static View make(Context context, String icon, int sizePx, int tint, ImageStore images) {
        if ("none".equals(icon)) return null;
        if (icon.startsWith("emoji:")) {
            TextView emoji = new TextView(context);
            emoji.setIncludeFontPadding(false);
            emoji.setSingleLine();
            emoji.setGravity(Gravity.CENTER);
            emoji.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
            emoji.setText(icon.substring(6));
            return emoji;
        }
        if (icon.startsWith("image:")) {
            Bitmap bitmap;
            try {
                bitmap = Overlay.decode(images.read(icon.substring(6)));
            } catch (Api.Failure | IOException unavailable) { return null; }
            if (bitmap == null) return null;
            ImageView picture = new ImageView(context);
            picture.setImageBitmap(bitmap);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            picture.setBackground(circle);
            picture.setClipToOutline(true);
            return picture;
        }
        return new Icons(context, icon, tint);
    }
}
