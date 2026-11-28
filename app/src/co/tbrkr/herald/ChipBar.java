package co.tbrkr.herald;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class ChipBar extends LinearLayout {
    ChipBar(Context context) { super(context); }

    void render(ChipLayout.Group group, Settings settings, ImageStore images, ChipPulse pulses,
            int height, int width) {
        removeAllViews();
        List<Slot> slots = new ArrayList<>();
        for (Map<String, Object> row : group.visible) {
            slots.add(new Slot(getContext(), row, settings.chipVisual((String) row.get("look")),
                    images, pulses.start((String) row.get("id")), height));
        }
        if (group.hidden > 0) {
            Map<String, Object> count = Json.fields("text", "+" + group.hidden, "icon", "none", "color", "#FFFFFF");
            slots.add(new Slot(getContext(), count, settings.chipVisual(null), images, -1, height));
        }
        int gap = Math.round(.022f * height) / 3;
        int available = Math.round(.94f * width) - gap * (slots.size() - 1);
        // Short labels leave their unused share to longer neighbors.
        List<Slot> shortestFirst = new ArrayList<>(slots);
        shortestFirst.sort(Comparator.comparingInt(s -> s.content.getMeasuredWidth()));
        int remaining = slots.size();
        for (Slot slot : shortestFirst) {
            slot.fit(available / remaining--);
            available -= slot.getMeasuredWidth();
        }
        setGravity(Gravity.CENTER_VERTICAL);
        for (Slot slot : slots) {
            LayoutParams lp = new LayoutParams(slot.getMeasuredWidth(), slot.getMeasuredHeight());
            if (getChildCount() > 0) lp.leftMargin = gap;
            addView(slot, lp);
        }
    }

    private static final class Slot extends FrameLayout {
        final LinearLayout content;
        final DropShadow shadow;
        final int glow, glowColor;
        final int[] edges;
        final boolean steadyGlow;
        final float smallCorner;
        final long started;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final GradientDrawable shape = new GradientDrawable();

        Slot(Context context, Map<String, Object> row, Map<String, Object> style, ImageStore images,
                long started, int h) {
            super(context);
            this.started = started;
            String color = (String) row.get("color");
            int tint = Color.parseColor(color);
            String resolvedGlow = ChipLayout.glowColor(style, color);
            steadyGlow = resolvedGlow != null;
            glowColor = (Color.parseColor(steadyGlow ? resolvedGlow : color) & 0xffffff) | 0x59000000;
            glow = Math.round(.006f * h);
            shadow = new DropShadow((String) style.get("shadow"), h);
            edges = CardFrame.insets(shadow.reach, shadow.offset, steadyGlow || started >= 0 ? glow : 0);
            smallCorner = "none".equals(style.get("corner")) ? .012f * h : 0;

            int size = Math.max(1, Math.round(.022f * h));
            content = new LinearLayout(context);
            content.setGravity(Gravity.CENTER_VERTICAL);
            int alpha = Math.round(255 * (int) style.get("opacity") / 100f);
            shape.setColor((Color.parseColor((String) style.get("background")) & 0xffffff) | alpha << 24);
            String border = (String) style.get("border");
            if (!"none".equals(border)) {
                float factor = "thin".equals(border) ? .001f : "medium".equals(border) ? .002f : .0035f;
                shape.setStroke(Math.max(1, Math.round(factor * h)), Color.parseColor((String) style.get("border_color")));
            }
            content.setBackground(shape);
            int horizontalPadding = 3 * size / 4;
            int verticalPadding = size / 3;
            content.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
            String icon = (String) row.get("icon");
            View glyph = IconViews.make(context, icon, size, tint, images);
            if (glyph != null) content.addView(glyph, size, size);
            String text = (String) row.get("text");
            if (!text.isEmpty()) {
                TextView label = new TextView(context);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                label.setTextColor(tint);
                label.setText(text);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                if (glyph != null) labelParams.setMarginStart(size / 3);
                content.addView(label, labelParams);
            }
            content.measure(0, 0);
            addView(content);
        }

        void fit(int available) {
            int inner = available - edges[0] - edges[2];
            int width = Math.min(content.getMeasuredWidth(), Math.max(1, inner));
            while (width > 1 && width + 2 * ChipLayout.room(width, 0)[0] > inner) width--;
            content.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), 0);
            int height = content.getMeasuredHeight();
            int[] room = ChipLayout.room(width, height);
            setPadding(edges[0] + room[0], edges[1] + room[1], edges[2] + room[0], edges[3] + room[1]);
            shape.setCornerRadius(smallCorner > 0 ? smallCorner : height / 2f);
            content.setLayoutParams(new FrameLayout.LayoutParams(width, height));
            measure(MeasureSpec.makeMeasureSpec(width + getPaddingLeft() + getPaddingRight(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height + getPaddingTop() + getPaddingBottom(), MeasureSpec.EXACTLY));
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            long elapsed = SystemClock.uptimeMillis() - started;
            boolean pulse = started >= 0 && elapsed < 1500;
            float scale = pulse ? 1f + .08f * (1f - Math.abs(elapsed % 500 - 250) / 250f) : 1f;
            float dx = content.getWidth() * (scale - 1) / 2, dy = content.getHeight() * (scale - 1) / 2;
            float left = content.getLeft() - dx, right = content.getRight() + dx;
            float top = content.getTop() - dy, bottom = content.getBottom() + dy;
            float radius = smallCorner > 0 ? smallCorner * scale : (bottom - top) / 2;
            int shadowSave = canvas.save();
            canvas.scale(scale, scale, (left+right)/2, (top+bottom)/2);
            shadow.draw(canvas, content.getLeft(), content.getTop(), content.getWidth(), content.getHeight(),
                    smallCorner > 0 ? smallCorner : content.getHeight() / 2f);
            canvas.restoreToCount(shadowSave);
            if (steadyGlow || pulse) {
                paint.setColor(glowColor);
                canvas.drawRoundRect(left-glow, top-glow, right+glow, bottom+glow, radius+glow, radius+glow, paint);
            }
            int save = canvas.save();
            canvas.scale(scale, scale, (left+right)/2, (top+bottom)/2);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
            if (pulse) postInvalidateOnAnimation();
        }
    }
}
