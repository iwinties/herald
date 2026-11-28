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
    private final TextView caption, pauseHint, snapshotTime;
    private final Runnable expirePreview = this::expirePreview;
    private final View backHint;
    private final LinearLayout buttons, copy, column;
    private final CameraControls cameraControls;
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
    private float presentedFull = -1, attentionGlow;
    private float videoAspect;
    void videoSize(int width, int height, float pixelRatio) {
        if (width <= 0 || height <= 0 || videoTexture == null) return;
        videoAspect = width * pixelRatio / height;
        // Keep the decoder texture at a stable source-sized buffer while its viewport changes.
        LayoutParams fixed = new LayoutParams(width,height,Gravity.TOP | Gravity.LEFT);
        videoTexture.setPivotX(0);
        videoTexture.setPivotY(0);
        videoTexture.setLayoutParams(fixed);
    }
    void presentation(float fullscreen) {
        if (presentedFull == fullscreen) return;
        presentedFull = fullscreen;
        requestLayout();
        decoration.invalidate();
    }
    void attentionGlow(float value) { attentionGlow = value; decoration.invalidate(); }

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
        int halo = "glow".equals(s.get("attention_animation")) ? Math.max(2, Math.round(.008f * sh)) : 0;
        insets = CardFrame.insets(shadow.reach, shadow.offset, Math.max(glow, halo));
        decoration = new View(context) {
            private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override protected void onDraw(Canvas canvas) {
                if (presentedFull == 1 || (presentedFull < 0 && full)) return;
                int left = insets[0], top = insets[1];
                int right = getWidth() - insets[2], bottom = getHeight() - insets[3];
                if (presentedFull > 0 && presentedFull < 1) {
                    float compactRadius = "pill".equals(corner) ? Math.min(compactWidth,compactMediaHeight)/2f
                            : Math.round(sh * ("small".equals(corner) ? .012f : "medium".equals(corner) ? .02f
                            : "large".equals(corner) ? .03f : 0f));
                    shadow.drawMoving(canvas,left,top,right-left,bottom-top,compactRadius);
                } else shadow.draw(canvas, left, top, right - left, bottom - top, frameRadius);
                if (attentionGlow > 0 && halo > 0) {
                    glowPaint.setColor((Math.round(110 * attentionGlow) << 24) | (accent & 0xffffff));
                    canvas.drawRoundRect(left-halo, top-halo, right+halo, bottom+halo,
                            frameRadius+halo, frameRadius+halo, glowPaint);
                }
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
        boolean ticker = "ticker".equals(s.get("text_animation"));
        boolean messageOnly = (ticker || "strip".equals(layout)) && spec.title.trim().isEmpty();
        boolean dot = "dot".equals(accentStyle);
        View glyph = dot ? new View(context)
                : IconViews.make(context, icon, titlePx, badge ? Color.WHITE : accent, images);
        if (glyph != null && !messageOnly) {
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
        if (!messageOnly && "category".equals(s.get("label"))) {
            TextView category = label(categoryName.toUpperCase(Locale.ROOT), body * .7f, false);
            category.setLetterSpacing(.12f);
            category.setAlpha(.8f);
            lines.addView(category);
        }
        boolean single = "strip".equals(layout) || "pill".equals(layout);
        boolean bold = "bold".equals(s.get("title_weight"));
        if (ticker) {
            lines.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
            TextView fixed = label(spec.title,title,bold);
            fixed.setTextColor(color(s,"title_color"));
            fixed.setSingleLine(true);
            fixed.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout tickerRow = new LinearLayout(context) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    int limit = Math.round(.35f*MeasureSpec.getSize(widthSpec));
                    if (fixed.getMaxWidth() != limit) fixed.setMaxWidth(limit);
                    super.onMeasure(widthSpec,heightSpec);
                }
            };
            tickerRow.setGravity(Gravity.CENTER_VERTICAL);
            if (!messageOnly && !spec.message.isEmpty()) {
                LinearLayout.LayoutParams heading = new LinearLayout.LayoutParams(-2,-2);
                heading.setMarginEnd(pad);
                tickerRow.addView(fixed,heading);
            }
            TickerText message = new TickerText(context, (String)s.get("ticker_speed"));
            message.setText(spec.message.isEmpty() ? spec.title : spec.message);
            message.setTextSize(TypedValue.COMPLEX_UNIT_PX,body);
            message.setTextColor(textColor);
            tickerRow.addView(message,new LinearLayout.LayoutParams(0,-2,1));
            lines.addView(tickerRow,new LinearLayout.LayoutParams(-1,-2));
        } else if (single) {
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
        if (!single && !ticker && !spec.message.isEmpty()) copy.addView(label(spec.message, body, false));
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
        if (spec.previewTimestamp > 0) {
            String localTime = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.MEDIUM)
                    .withLocale(Locale.getDefault()).withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochSecond(spec.previewTimestamp));
            snapshotTime = label("Snapshot · " + localTime, body * .8f, true);
            snapshotTime.setTextColor(Color.WHITE);
            snapshotTime.setBackgroundColor(0xcc151515);
            snapshotTime.setPadding(pad/2, pad/4, pad/2, pad/4);
            snapshotTime.setVisibility(GONE);
            LayoutParams badgePosition = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            badgePosition.topMargin = pad/2;
            badgePosition.setMarginStart(pad/2);
            still.addView(snapshotTime, badgePosition);
        } else snapshotTime = null;
        caption = label("", body, true);
        caption.setGravity(Gravity.CENTER);
        caption.setBackgroundColor(0xc0000000);
        caption.setVisibility(GONE);
        // Keep status in the text area so fullscreen copy cannot cover it.
        if (spec.video == null) copy.addView(caption);
        else media.addView(caption, new LayoutParams(-2, -2, Gravity.CENTER));
        cameraControls = spec.video != null && !spec.buttons.isEmpty() ? new CameraControls(context, pad, bg) : null;
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
                LinearLayout.LayoutParams cell;
                if (cameraControls != null) {
                    label.setPadding(pad, gap, pad, gap);
                    label.setMinHeight(Math.round(sh * .043f));
                    label.setMaxLines(2);
                    label.setEllipsize(TextUtils.TruncateAt.END);
                    label.setMaxWidth(Math.max(1, compactWidth - 2*pad));
                    cell = new LinearLayout.LayoutParams(-2, -1);
                } else {
                    label.setPadding(gap, gap, gap, gap);
                    cell = new LinearLayout.LayoutParams(0, -1, 1f);
                }
                if (buttons.getChildCount() > 0) cell.setMarginStart(gap);
                buttons.addView(label, cell);
            }
            backHint = makeBackHint(body, pad);
            backHint.setVisibility(GONE);
            pauseHint = label("Pause to use buttons", body * .8f, false);
            pauseHint.setVisibility(GONE);
            if (cameraControls != null) {
                cameraControls.addButtons(buttons);
                cameraControls.addView(backHint, new LinearLayout.LayoutParams(-2, -2));
                cameraControls.addView(pauseHint, new LinearLayout.LayoutParams(-2, -2));
            } else {
                LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
                row.topMargin = 2 * gap;
                copy.addView(buttons, row);
                LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
                hintParams.topMargin = gap;
                copy.addView(backHint, hintParams);
                copy.addView(pauseHint, new LinearLayout.LayoutParams(hintParams));
            }
        } else {
            backHint = null;
            pauseHint = null;
        }
        media.setVisibility(spec.video == null ? GONE : VISIBLE);
        column.addView(copy);
        column.addView(media, new LinearLayout.LayoutParams(-1, compactMediaHeight));
        if (cameraControls != null) column.addView(cameraControls, new LinearLayout.LayoutParams(-2,-2));
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

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed,l,t,r,b);
        int w = r-l, h = b-t;
        float fraction = presentedFull < 0 ? (full ? 1 : 0) : presentedFull;
        float radius = "pill".equals(corner) ? h/2f : screenHeight * ("small".equals(corner) ? .012f
                : "medium".equals(corner) ? .02f : "large".equals(corner) ? .03f : 0f);
        setFrameRadius(Math.round(radius) * (1-fraction));
        if (videoTexture != null) {
            layoutCamera(w, h, fraction);
        } else if (presentedFull >= 0 && (fraction > 0 || full)) {
            int copyHeight = copy.getMeasuredHeight();
            int top = Math.round((h-copyHeight)*fraction);
            column.layout(0,0,w,h);
            copy.layout(0,top,w,top+copyHeight);
            copy.setTranslationZ(1);
            int mediaTop = Math.round(copyHeight*(1-fraction));
            int mediaHeight = Math.max(1,h-mediaTop);
            media.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(mediaHeight, MeasureSpec.EXACTLY));
            media.layout(0,mediaTop,w,h);
        } else copy.setTranslationZ(0);
        if (videoTexture != null && videoAspect > 0 && media.getWidth() > 0 && media.getHeight() > 0) {
            float ratio = videoAspect * media.getHeight()/media.getWidth();
            float fitX = Math.min(1,ratio), fitY = Math.min(1,1/ratio);
            float width = media.getWidth()*CardMotion.mix(fitX,Math.max(1,ratio),fraction);
            float height = media.getHeight()*CardMotion.mix(fitY,Math.max(1,1/ratio),fraction);
            videoTexture.setScaleX(width/videoTexture.getLayoutParams().width);
            videoTexture.setScaleY(height/videoTexture.getLayoutParams().height);
            videoTexture.setTranslationX((media.getWidth()-width)/2);
            videoTexture.setTranslationY((media.getHeight()-height)/2);
        }
    }

    /** Keep a single media hierarchy through a resize, including when the remote reverses it. */
    private void layoutCamera(int w, int h, float fraction) {
        column.layout(0, 0, w, h);
        int header = copy.getMeasuredHeight();
        int controlsHeight = cameraControls == null ? 0 : cameraControls.getMeasuredHeight();
        int top = Math.round(header * (1 - fraction));
        int bottom = Math.max(top + 1, h - Math.round(controlsHeight * (1 - fraction)));
        copy.layout(0, top - header, w, top);
        copy.setAlpha(1 - fraction);
        copy.setImportantForAccessibility(fraction == 1 ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (media.getMeasuredWidth() != w || media.getMeasuredHeight() != bottom - top)
            media.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(bottom - top, MeasureSpec.EXACTLY));
        media.layout(0, top, w, bottom);
        bar.setAlpha(1 - fraction);
        if (cameraControls == null) return;
        int controlsWidth = cameraControls.getMeasuredWidth();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int left = Math.round(CardMotion.mix(rtl ? w - controlsWidth : 0,(w-controlsWidth)/2f,fraction));
        int baseline = h - Math.round(screenHeight * .035f * fraction);
        cameraControls.layout(left, baseline-controlsHeight, left+controlsWidth, baseline);
        cameraControls.getBackground().setAlpha(Math.round(232 * fraction));
        cameraControls.reveal(buttons.getChildAt(selected));
    }

    private static final class CameraControls extends LinearLayout {
        private final int gap;
        private final android.widget.HorizontalScrollView actions;

        CameraControls(Context context, int pad, int background) {
            super(context);
            gap = Math.max(2, pad/2);
            setPadding(pad, gap, pad, gap);
            setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(background | 0xff000000);
            surface.setCornerRadius(pad);
            surface.setAlpha(0);
            setBackground(surface);
            actions = new android.widget.HorizontalScrollView(context);
            actions.setHorizontalScrollBarEnabled(false);
            actions.setHorizontalFadingEdgeEnabled(true);
            actions.setFocusable(false);
            actions.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        }

        void addButtons(LinearLayout buttons) {
            actions.addView(buttons, new FrameLayout.LayoutParams(-2, -2));
            addView(actions, new LinearLayout.LayoutParams(-2, -2));
        }

        void reveal(View selected) {
            int viewport = actions.getWidth();
            if (viewport <= 0) return;
            int left = actions.getScrollX();
            if (selected.getLeft() < left) actions.scrollTo(selected.getLeft(), 0);
            else if (selected.getRight() > left + viewport) actions.scrollTo(selected.getRight() - viewport, 0);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            int natural = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
                natural += child.getMeasuredWidth() + (i == 0 ? 0 : gap);
            }
            boolean stacked = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && natural > available;
            setOrientation(stacked ? VERTICAL : HORIZONTAL);
            for (int i = 1; i < getChildCount(); i++) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)getChildAt(i).getLayoutParams();
                lp.setMarginStart(stacked ? 0 : gap);
                lp.topMargin = stacked ? gap : 0;
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private void setFrameRadius(float radius) {
        if (frameRadius == radius) return;
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
        if (videoTexture == null && full) {
            column.removeView(copy);
            copy.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0, 0xe6000000}));
            addView(copy, indexOfChild(bar), new LayoutParams(-1, -2, Gravity.BOTTOM));
            setFrameRadius(0);
        } else if (videoTexture == null) {
            removeView(copy);
            copy.setBackground(null);
            column.addView(copy, 0, new LinearLayout.LayoutParams(-1, -2));
        }
        picture.setScaleType(full ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (videoTexture == null) {
            column.setLayoutParams(new LayoutParams(-1, full ? -1 : -2));
            media.setLayoutParams(new LinearLayout.LayoutParams(-1, full ? -1 : compactMediaHeight));
        }
        for (int i = 0; i < spec.buttons.size(); i++) {
            Map<String, Object> button = spec.buttons.get(i);
            if ("expand".equals(button.get("action"))) {
                ((TextView) buttons.getChildAt(i)).setText(full ? "Minimize" : (String) button.get("label"));
            }
        }
    }

    void freezeVideo() {
        if (videoTexture == null || !videoTexture.isAvailable() || still.getVisibility() == VISIBLE) return;
        int width = Math.min(640, videoTexture.getWidth());
        if (width <= 0) return;
        int height = Math.max(1, Math.round(width * (float)videoTexture.getHeight()/videoTexture.getWidth()));
        Bitmap last = videoTexture.getBitmap(width, Math.min(640,height));
        if (last != null) {
            removeCallbacks(expirePreview);
            if (snapshotTime != null) snapshotTime.setVisibility(GONE);
            picture.setImageBitmap(last);
        }
        if (placeholder != null) placeholder.setVisibility(GONE);
        still.setVisibility(VISIBLE);
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
        if (cameraControls != null) cameraControls.reveal(buttons.getChildAt(selected));
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

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(expirePreview);
        super.onDetachedFromWindow();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (snapshotTime != null && snapshotTime.getVisibility() == VISIBLE) schedulePreviewExpiry();
    }

    private void schedulePreviewExpiry() {
        removeCallbacks(expirePreview);
        long remaining = (spec.previewTimestamp + CardSpec.PREVIEW_AGE_SECONDS + 1)*1000 - System.currentTimeMillis();
        if (remaining <= 0) expirePreview();
        else postDelayed(expirePreview, remaining);
    }

    private void expirePreview() {
        imagePending = false;
        picture.setImageDrawable(null);
        snapshotTime.setVisibility(GONE);
        if (placeholder != null) placeholder.setVisibility(VISIBLE);
    }

    void picture(Bitmap bitmap) {
        if (snapshotTime != null) {
            long now = System.currentTimeMillis();
            if (!CardSpec.freshPreview(spec.previewTimestamp, now/1000)) return;
            snapshotTime.setVisibility(VISIBLE);
            schedulePreviewExpiry();
        }
        imagePending = true;
        picture.setImageBitmap(bitmap);
        if (placeholder != null) placeholder.setVisibility(GONE);
        // A preview may arrive behind live video. Keep the stream viewport stable.
        if (snapshotTime != null) {
            reportVisibleImage();
            return;
        }
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
