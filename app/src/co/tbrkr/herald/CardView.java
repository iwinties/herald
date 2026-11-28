package co.tbrkr.herald;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import java.util.Map;

final class CardView extends FrameLayout {
    private static final int[][] BUTTON_STATES = {{android.R.attr.state_selected}, {}};
    final CardSpec spec;
    String placement;
    int width;
    final TextureView videoTexture;
    private final FrameLayout still;
    private final TextView caption, pauseHint;
    private final View backHint;
    private final LinearLayout buttons, copy, column;
    private int selected;
    private boolean canSelect;
    private final Runnable imageShown;
    private boolean imagePending;
    private final ImageView picture;
    private final FrameLayout media;
    private boolean full;
    private final String compactPlacement;
    private final int compactWidth;
    private int compactMediaHeight;
    private final int screenHeight;
    private final View placeholder;
    private final View bar;
    final int[] insets;
    final View decoration;
    final String layout;
    private final GradientDrawable shape = new GradientDrawable();
    private final GradientDrawable stroke = new GradientDrawable();
    private final String corner;
    private final int textColor;
    private float frameRadius;

    CardView(Context context, CardBoard.Entry e, int sw, int sh, String categoryName, ImageStore images, Runnable imageShown) {
        super(context);
        this.imageShown = imageShown;
        screenHeight = sh;
        spec = e.spec;
        Map<String, Object> s = e.appearance.fields;
        placement = (String) s.get("placement");
        boolean startsFullscreen = "fullscreen".equals(placement);
        if (startsFullscreen) placement = "top_right";
        String size = (String) s.get("size");
        layout = spec.image != null || spec.video != null || !spec.buttons.isEmpty()
                ? "card" : (String) s.get("layout");
        if ("strip".equals(layout)) {
            placement = placement.startsWith("bottom") ? "bottom" : "top";
        }
        compactPlacement = placement;
        width = "strip".equals(layout) || ("full".equals(size) && !startsFullscreen) ? sw : Math.round(sw * (size.equals("small") ? .24f : size.equals("large") ? .44f : .32f));
        compactWidth = width;
        compactMediaHeight = Math.min(sh / 2, width * 9 / 16);
        int accent = Color.parseColor((String) s.get("accent"));
        int bg = Color.parseColor((String) s.get("background")) & 0xffffff;
        bg |= Math.round(255 * (int) s.get("opacity") / 100f) << 24;
        corner = (String) s.get("corner");
        textColor = color(s, "text_color");
        String borderStyle = (String) s.get("border");
        int border = "none".equals(borderStyle) ? 0 : Math.max(1, Math.round(sh *
                ("thin".equals(borderStyle) ? .001f : "medium".equals(borderStyle) ? .002f : .0035f)));
        DropShadow shadow = new DropShadow((String) s.get("shadow"), sh);
        int glow = "none".equals(s.get("glow")) ? 0 : Math.round(.006f * sh);
        int glowColor = "border".equals(s.get("glow")) ? color(s, "border_color") : accent;
        insets = CardFrame.insets(shadow.reach, shadow.offset, glow);
        decoration = new View(context) {
            private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override protected void onDraw(Canvas canvas) {
                if (full) return;
                int left = insets[0], top = insets[1];
                int right = getWidth() - insets[2], bottom = getHeight() - insets[3];
                shadow.draw(canvas, left, top, right - left, bottom - top, frameRadius);
                if (glow > 0) {
                    glowPaint.setColor(0x59000000 | (glowColor & 0xffffff));
                    canvas.drawRoundRect(left - glow, top - glow, right + glow, bottom + glow,
                            frameRadius + glow, frameRadius + glow, glowPaint);
                }
            }
        };
        if (!"text".equals(layout)) {
            shape.setColor(bg);
            if (border > 0) {
                stroke.setColor(Color.TRANSPARENT);
                stroke.setStroke(border, color(s, "border_color"));
                setForeground(stroke);
            }
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
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        placeholder = spec.video != null ? IconViews.make(context, icon, pad * 3, accent, images) : null;
        still = new FrameLayout(context);
        videoTexture = spec.video != null ? new TextureView(context) : null;
        if (videoTexture != null) {
            media.addView(videoTexture, new LayoutParams(-1,-1));
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
        if (!spec.buttons.isEmpty()) {
            String spacing = (String) s.get("button_spacing");
            int gap = Math.max(2, Math.round(pad * ("compact".equals(spacing) ? .35f :
                    "roomy".equals(spacing) ? .8f : .55f)));
            String finish = (String) s.get("button_style");
            int line = Math.max(2, Math.round(sh * .0025f));
            String buttonShape = (String) s.get("button_shape");
            float radius = "pill".equals(buttonShape) ? sh : "square".equals(buttonShape) ? 0 : pad / 2f;
            for (Map<String, Object> button : spec.buttons) {
                TextView label = label((String) button.get("label"), body, true);
                label.setGravity(Gravity.CENTER);
                Map<String, Object> colors = button.containsKey("style")
                        ? Settings.object(button.get("style"), "buttons") : java.util.Collections.emptyMap();
                int normalFill = buttonColor(s, colors, "background");
                int focusFill = buttonColor(s, colors, "focus_background");
                int normalText = buttonColor(s, colors, "text_color");
                int focusText = buttonColor(s, colors, "focus_text_color");
                StateListDrawable states = new StateListDrawable();
                states.addState(BUTTON_STATES[0], buttonSurface(finish, focusFill, focusText, radius, line, true));
                states.addState(BUTTON_STATES[1], buttonSurface(finish, normalFill, normalText, radius, line, false));
                label.setBackground(states);
                label.setTextColor(new ColorStateList(BUTTON_STATES, new int[]{
                        focusText, normalText}));
                label.setPadding(gap, gap, gap, gap);
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1f);
                if (buttons.getChildCount() > 0) cell.setMarginStart(gap);
                buttons.addView(label, cell);
            }
            LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
            row.topMargin = 2 * gap;
            copy.addView(buttons, row);
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
            hintParams.topMargin = gap;
            backHint = makeBackHint(body, pad);
            backHint.setVisibility(GONE);
            copy.addView(backHint, hintParams);
            pauseHint = label("Pause to use buttons", body * .8f, false);
            pauseHint.setVisibility(GONE);
            copy.addView(pauseHint, new LinearLayout.LayoutParams(hintParams));
        } else {
            backHint = null;
            pauseHint = null;
        }
        media.setVisibility(spec.video == null ? GONE : VISIBLE);
        column.addView(copy);
        column.addView(media, new LinearLayout.LayoutParams(-1, compactMediaHeight));
        addView(column, new LayoutParams(-1,-2));
        bar = new View(context);
        bar.setBackgroundColor(accent);
        // Height 0 here: onMeasure gives the bar the card's height. A MATCH_PARENT bar would take the
        // whole AT_MOST screen height and stretch every non-fullscreen card to a full-height column.
        if ("underline".equals(accentStyle)) addView(bar, new LayoutParams(-1, 2, Gravity.BOTTOM));
        else if ("bar".equals(accentStyle)) addView(bar, new LayoutParams(Math.max(1, Math.round(.004f * sh)), 0,
                placement.endsWith("left") ? Gravity.RIGHT : Gravity.LEFT));
        setExpanded(e.expanded, sw);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        if (bar.getParent() != null && bar.getLayoutParams().height == 0) bar.measure(MeasureSpec.makeMeasureSpec(bar.getLayoutParams().width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
    }

    private View makeBackHint(float body, int pad) {
        LinearLayout hint = new LinearLayout(getContext());
        hint.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        hint.setContentDescription("Back to close");
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TextView key = label("Back", body * .72f, true);
        key.setIncludeFontPadding(false);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setStroke(Math.max(1, Math.round(screenHeight * .001f)), (textColor & 0x00ffffff) | 0x99000000);
        outline.setCornerRadius(pad * .24f);
        key.setBackground(outline);
        key.setPaddingRelative(pad / 3, 1, pad / 3, 1);
        key.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hint.addView(key);
        TextView action = label("to close", body * .72f, false);
        action.setIncludeFontPadding(false);
        action.setAlpha(.8f);
        action.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(Math.max(2, pad / 3));
        hint.addView(action, params);
        return hint;
    }

    private static int color(Map<String, Object> fields, String key) {
        return Color.parseColor((String) fields.get(key));
    }

    private static int buttonColor(Map<String, Object> style, Map<String, Object> overrides, String key) {
        return Color.parseColor((String) overrides.getOrDefault(key, style.get("button_" + key)));
    }

    private static GradientDrawable buttonFace(int fill, float radius) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(fill);
        surface.setCornerRadius(radius);
        return surface;
    }

    private static Drawable buttonSurface(String finish, int fill, int text, float radius, int line, boolean selected) {
        GradientDrawable face = buttonFace(fill, radius);
        int contrast = Color.luminance(fill) > .179f ? Color.BLACK : Color.WHITE;
        if (selected) face.setStroke(line, (contrast & 0xffffff) | 0xd9000000);
        else if ("outline".equals(finish)) face.setStroke(line, (text & 0xffffff) | 0x99000000);
        if ("raised".equals(finish)) {
            LayerDrawable raised = new LayerDrawable(new Drawable[]{buttonFace(Color.rgb(
                    Color.red(fill) / 2, Color.green(fill) / 2, Color.blue(fill) / 2), radius), face});
            raised.setLayerInset(1, 0, 0, 0, 2 * line);
            return raised;
        }
        if ("underline".equals(finish)) {
            GradientDrawable rule = buttonFace((text & 0xffffff) | (selected ? 0xe6000000 : 0x99000000), line);
            LayerDrawable underlined = new LayerDrawable(new Drawable[]{face, rule});
            underlined.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
            underlined.setLayerHeight(1, line);
            underlined.setLayerInset(1, 4 * line, 0, 4 * line, line);
            return underlined;
        }
        return face;
    }

    private static GradientDrawable circle(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        return background;
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        float radius = full ? 0 : "pill".equals(corner) ? h / 2f
                : Math.round(screenHeight * ("small".equals(corner) ? .012f : "medium".equals(corner) ? .02f
                        : "large".equals(corner) ? .03f : 0f));
        setFrameRadius(radius);
    }

    private void setFrameRadius(float radius) {
        frameRadius = radius;
        decoration.invalidate();
        shape.setCornerRadius(radius);
        stroke.setCornerRadius(radius);
        invalidateOutline();
    }

    void setExpanded(boolean expanded, int screenWidth) {
        if (full == expanded || "strip".equals(layout)) return;
        full = expanded;
        placement = full ? "fullscreen" : compactPlacement;
        width = full ? screenWidth : compactWidth;
        // Only move the caption and controls. The media view stays attached to its decoder.
        if (full) {
            column.removeView(copy);
            copy.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0, 0xe6000000}));
            addView(copy, indexOfChild(bar), new LayoutParams(-1, -2, Gravity.BOTTOM));
            setFrameRadius(0);
        } else {
            removeView(copy);
            copy.setBackground(null);
            column.addView(copy, 0, new LinearLayout.LayoutParams(-1, -2));
        }
        picture.setScaleType(full ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        column.setLayoutParams(new LayoutParams(-1, full ? -1 : -2));
        media.setLayoutParams(new LinearLayout.LayoutParams(-1, full ? -1 : compactMediaHeight));
        for (int i = 0; i < spec.buttons.size(); i++) {
            Map<String, Object> button = spec.buttons.get(i);
            if ("expand".equals(button.get("action"))) {
                ((TextView) buttons.getChildAt(i)).setText(full ? "Minimize" : (String) button.get("label"));
            }
        }
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
        backHint.setVisibility(owner && !paused ? VISIBLE : GONE);
        pauseHint.setVisibility(paused ? VISIBLE : GONE);
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
        }
    }

    void picture(Bitmap bitmap) {
        imagePending = true;
        picture.setImageBitmap(bitmap);
        if (placeholder != null) placeholder.setVisibility(GONE);
        compactMediaHeight = Math.min(screenHeight / 2, Math.max(1,
                Math.round(compactWidth * (float) bitmap.getHeight() / bitmap.getWidth())));
        if (!full) media.getLayoutParams().height = compactMediaHeight;
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
