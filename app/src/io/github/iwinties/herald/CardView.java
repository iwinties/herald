package io.github.iwinties.herald;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

final class CardView extends FrameLayout {
    final CardSpec spec;
    String placement;
    int width;
    final SurfaceView surface;
    private final FrameLayout still;
    private final TextView caption, hint;
    private final LinearLayout buttons, copy, column;
    private int selected;
    private boolean canSelect;
    private final Runnable imageShown;
    private boolean imagePending;
    private final ImageView picture;
    private final FrameLayout media;
    private boolean full;
    private final int screenHeight;
    private final View placeholder;
    private final View bar;
    final int[] insets;
    final View decoration;
    final String layout;
    private final GradientDrawable shape = new GradientDrawable();
    private final String corner;
    private final int textColor;
    private final ArrayList<GradientDrawable> halos = new ArrayList<>();
    private final ArrayList<Integer> spreads = new ArrayList<>();

    CardView(Context context, CardBoard.Entry e, int sw, int sh, String categoryName, ImageStore images, Runnable imageShown) {
        super(context);
        this.imageShown = imageShown;
        screenHeight = sh;
        spec = e.spec;
        Map<String, Object> s = e.appearance.fields;
        placement = (String) s.get("placement");
        full = "fullscreen".equals(placement);
        String size = (String) s.get("size");
        layout = spec.image != null || spec.video != null || !spec.buttons.isEmpty()
                ? "card" : (String) s.get("layout");
        if ("strip".equals(layout)) {
            placement = placement.startsWith("bottom") ? "bottom" : "top";
            full = false;
        }
        width = full || "strip".equals(layout) || "full".equals(size) ? sw : Math.round(sw * (size.equals("small") ? .24f : size.equals("large") ? .44f : .32f));
        int accent = Color.parseColor((String) s.get("accent"));
        int bg = Color.parseColor((String) s.get("background")) & 0xffffff;
        bg |= Math.round(255 * (int) s.get("opacity") / 100f) << 24;
        corner = (String) s.get("corner");
        textColor = color(s, "text_color");
        String borderStyle = (String) s.get("border");
        int border = "none".equals(borderStyle) ? 0 : Math.max(1, Math.round(sh *
                ("thin".equals(borderStyle) ? .001f : "medium".equals(borderStyle) ? .002f : .0035f)));
        int spread = Math.round(sh * ("soft".equals(s.get("shadow")) ? .008f
                : "strong".equals(s.get("shadow")) ? .016f : 0f));
        int offset = spread == 0 ? 0 : Math.round(.004f * sh);
        int glow = "none".equals(s.get("glow")) ? 0 : Math.round(.006f * sh);
        insets = CardFrame.insets(spread, offset, glow);
        decoration = new View(context);
        if (spread > 0) addHalo(spread, "strong".equals(s.get("shadow")) ? 0x8C000000 : 0x59000000);
        if (glow > 0) {
            int glowColor = "border".equals(s.get("glow")) ? color(s, "border_color") : accent;
            addHalo(glow, 0x59000000 | (glowColor & 0xffffff));
        }
        if (!halos.isEmpty()) {
            LayerDrawable layers = new LayerDrawable(halos.toArray(new Drawable[0]));
            layers.setPaddingMode(LayerDrawable.PADDING_MODE_STACK);
            for (int i = 0; i < halos.size(); i++) {
                int radius = spreads.get(i);
                int dy = i == 0 && spread > 0 ? offset : 0;
                layers.setLayerInset(i, insets[0] - radius, insets[1] - radius + dy,
                        insets[2] - radius, insets[3] - radius - dy);
            }
            decoration.setBackground(layers);
        }
        if (!"text".equals(layout)) {
            shape.setColor(bg);
            if (border > 0) shape.setStroke(border, color(s, "border_color"));
            setBackground(shape);
            setClipToOutline(true);
        }

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(.018f * sh);
        copy.setPadding(pad, pad, pad, pad);
        String scale = (String) s.get("text_size");
        int index = java.util.Arrays.asList("small","medium","large","xlarge").indexOf(scale);
        float title = sh * new float[]{.024f,.030f,.038f,.046f}[index];
        float body = sh * new float[]{.018f,.022f,.028f,.034f}[index];
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        String icon = (String) s.get("icon");
        String accentStyle = (String) s.get("accent_style");
        boolean badge = "badge".equals(accentStyle);
        int titlePx = Math.round(title);
        boolean dot = "dot".equals(accentStyle);
        View glyph = dot ? new View(context)
                : IconViews.make(context, icon, titlePx, badge ? Color.WHITE : accent, images);
        if (glyph != null) {
            int side = dot ? Math.round(.3f * title) : titlePx;
            if (dot) glyph.setBackground(circle(accent));
            if (badge) {
                FrameLayout disc = new FrameLayout(context);
                disc.setBackground(circle(accent));
                disc.addView(glyph, new LayoutParams(titlePx, titlePx, Gravity.CENTER));
                glyph = disc;
                side = Math.round(1.4f * title);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
            lp.rightMargin = pad / 2;
            header.addView(glyph, lp);
        }
        LinearLayout lines = new LinearLayout(context);
        lines.setOrientation(LinearLayout.VERTICAL);
        header.addView(lines, new LinearLayout.LayoutParams(-2, -2));
        if ("category".equals(s.get("label"))) {
            TextView category = label(categoryName.toUpperCase(Locale.ROOT), body * .7f, false);
            category.setLetterSpacing(.12f);
            category.setAlpha(.8f);
            lines.addView(category);
        }
        boolean single = "strip".equals(layout) || "pill".equals(layout);
        boolean bold = "bold".equals(s.get("title_weight"));
        if (single) {
            TextView line = label("", body, false);
            SpannableStringBuilder value = new SpannableStringBuilder(spec.title);
            int end = value.length();
            if (end > 0) {
                value.setSpan(new AbsoluteSizeSpan(titlePx), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                value.setSpan(new ForegroundColorSpan(color(s, "title_color")), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (bold) value.setSpan(new StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (!spec.message.isEmpty()) value.append("pill".equals(layout) ? " · " : "  ");
            }
            line.setText(value.append(spec.message));
            line.setSingleLine();
            line.setEllipsize(TextUtils.TruncateAt.END);
            lines.addView(line);
        } else if (!spec.title.isEmpty()) {
            TextView heading = label(spec.title, title, bold);
            heading.setTextColor(color(s, "title_color"));
            lines.addView(heading);
        }
        copy.addView(header);
        if (!single && !spec.message.isEmpty()) copy.addView(label(spec.message, body, false));
        if (!single && !spec.from.isEmpty()) {
            TextView from = label("From " + spec.from, body * .8f, false);
            from.setAlpha(.7f);
            copy.addView(from);
        }
        media = new FrameLayout(context);
        picture = new ImageView(context);
        picture.setScaleType(full ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        placeholder = spec.video != null ? IconViews.make(context, icon, pad * 3, accent, images) : null;
        still = new FrameLayout(context);
        surface = spec.video != null ? new SurfaceView(context) : null;
        if (surface != null) {
            media.addView(surface, new LayoutParams(-1,-1));
            still.setBackgroundColor(0xff181818);
        }
        media.addView(still, new LayoutParams(-1,-1));
        if (placeholder != null) still.addView(placeholder, new LayoutParams(pad * 3, pad * 3, Gravity.CENTER));
        still.addView(picture, new LayoutParams(-1,-1));
        caption = label("", body, true);
        caption.setGravity(Gravity.CENTER);
        caption.setBackgroundColor(0xc0000000);
        caption.setVisibility(GONE);
        // Keep status in the text area so fullscreen copy cannot cover it.
        copy.addView(caption);
        buttons = new LinearLayout(context);
        hint = label("", body * .8f, false);
        if (!spec.buttons.isEmpty()) {
            for (Map<String, Object> button : spec.buttons) {
                TextView label = label((String) button.get("label"), body, true);
                label.setGravity(Gravity.CENTER);
                label.setPadding(pad / 3, pad / 3, pad / 3, pad / 3);
                buttons.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            copy.addView(buttons);
            copy.addView(hint);
        }
        media.setVisibility(spec.video == null ? GONE : VISIBLE);
        if (full) {
            addView(media, new LayoutParams(-1,-1));
            copy.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0,0xe6000000}));
            addView(copy, new LayoutParams(-1,-2,Gravity.BOTTOM));
        } else {
            column.addView(copy);
            column.addView(media, new LinearLayout.LayoutParams(-1, Math.min(sh/2, width*9/16)));
            addView(column, new LayoutParams(-1,-2));
        }
        bar = new View(context);
        bar.setBackgroundColor(accent);
        // Height 0 here: onMeasure gives the bar the card's height. A MATCH_PARENT bar would take the
        // whole AT_MOST screen height and stretch every non-fullscreen card to a full-height column.
        if ("underline".equals(accentStyle)) addView(bar, new LayoutParams(-1, 2, Gravity.BOTTOM));
        else if ("bar".equals(accentStyle)) addView(bar, new LayoutParams(Math.max(1, Math.round(.004f * sh)), 0,
                placement.endsWith("left") ? Gravity.RIGHT : Gravity.LEFT));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        if (bar.getParent() != null && bar.getLayoutParams().height == 0) bar.measure(MeasureSpec.makeMeasureSpec(bar.getLayoutParams().width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
    }

    private static int color(Map<String, Object> fields, String key) {
        return Color.parseColor((String) fields.get(key));
    }

    private static GradientDrawable circle(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        return background;
    }

    private void addHalo(int spread, int color) {
        GradientDrawable halo = new GradientDrawable();
        halo.setColor(color);
        halos.add(halo);
        spreads.add(spread);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        float radius = full ? 0 : "pill".equals(corner) ? h / 2f
                : Math.round(screenHeight * ("small".equals(corner) ? .012f : "medium".equals(corner) ? .02f
                        : "large".equals(corner) ? .03f : 0f));
        shape.setCornerRadius(radius);
        for (int i = 0; i < halos.size(); i++) halos.get(i).setCornerRadius(radius + spreads.get(i));
    }

    void expand(int screenWidth) {
        if (full) return;
        full = true;
        placement = "fullscreen";
        width = screenWidth;
        shape.setCornerRadius(0);
        // The SurfaceView stays attached to its parent throughout expansion.
        column.removeView(copy);
        column.setLayoutParams(new LayoutParams(-1,-1));
        media.setLayoutParams(new LinearLayout.LayoutParams(-1,-1));
        copy.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0,0xe6000000}));
        addView(copy, new LayoutParams(-1,-2,Gravity.BOTTOM));
    }

    void showVideo(boolean playing, String message) {
        still.setVisibility(playing ? GONE : VISIBLE);
        reportVisibleImage();
        caption.setText(message);
        caption.setVisibility(message.isEmpty() ? GONE : VISIBLE);
    }

    void buttonFocus(boolean owner, boolean paused) {
        if (spec.buttons.isEmpty()) return;
        canSelect = owner;
        String text = paused ? "Pause to use buttons" : owner ? "BACK to close" : "";
        if (!text.contentEquals(hint.getText())) hint.setText(text);
        hint.setVisibility(text.isEmpty() ? GONE : VISIBLE);
        buttons.setAlpha(paused ? .5f : 1f);
        highlight();
    }

    void move(int direction) {
        selected = Math.max(0, Math.min(buttons.getChildCount() - 1, selected + direction));
        highlight();
    }

    int selected() { return selected; }

    private void highlight() {
        for (int i = 0; i < buttons.getChildCount(); i++) {
            TextView button = (TextView) buttons.getChildAt(i);
            boolean lit = canSelect && selected == i;
            if (button.isSelected() == lit) continue;
            button.setSelected(lit);
            button.setTextColor(lit ? Color.BLACK : Color.WHITE);
            button.setBackgroundColor(lit ? Color.WHITE : Color.TRANSPARENT);
        }
    }

    void picture(Bitmap bitmap) {
        imagePending = true;
        picture.setImageBitmap(bitmap);
        if (placeholder != null) placeholder.setVisibility(GONE);
        if (!full) media.getLayoutParams().height = Math.min(screenHeight/2,
                Math.max(1, Math.round(width * (float) bitmap.getHeight() / bitmap.getWidth())));
        media.setVisibility(VISIBLE);
        media.requestLayout();
        reportVisibleImage();
    }

    private void reportVisibleImage() {
        if (imagePending && still.getVisibility() == VISIBLE) {
            imagePending = false;
            imageShown.run();
        }
    }

    private TextView label(String value, float size, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTextColor(textColor);
        if ("text".equals(layout)) t.setShadowLayer(.004f * screenHeight, 0, .001f * screenHeight, 0xE6000000);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
}
