package co.tbrkr.herald;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/** A scaled TV scene. Both sizes contain the actual card and its decoration. */
final class LookPreview extends FrameLayout {
    private final FrameLayout stage;
    private final int screenWidth, screenHeight;
    private final boolean thumbnail;
    private final CardView card;
    private final CardMotion.Frame compact;
    private CardMotion.Frame current;
    private boolean expanded;
    private android.animation.ValueAnimator animation;

    LookPreview(Context context, CardSpec spec, boolean thumbnail) {
        super(context);
        this.thumbnail = thumbnail;
        DisplayMetrics screen = getResources().getDisplayMetrics();
        screenWidth = screen.widthPixels;
        screenHeight = screen.heightPixels;
        stage = new FrameLayout(context);
        stage.setPivotX(0);
        stage.setPivotY(0);
        stage.setBackgroundResource(R.drawable.look_scene);
        addView(stage, new LayoutParams(screenWidth, screenHeight, Gravity.TOP | Gravity.LEFT));

        CardBoard.Entry entry = new CardBoard.Entry(spec, 0, 0, 0);
        expanded = entry.expanded;
        entry.expanded = false;
        card = new CardView(context, entry,
                screenWidth, screenHeight, "General", null, () -> {});
        boolean wrap = "pill".equals(card.layout) && !"full".equals(spec.style.fields.get("size"));
        card.measure(MeasureSpec.makeMeasureSpec(card.width, wrap ? MeasureSpec.AT_MOST : MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(screenHeight, MeasureSpec.AT_MOST));
        int w = card.getMeasuredWidth(), h = card.getMeasuredHeight();
        int[][] origin = new int[1][2];
        int[] edge = card.insets;
        CardFrame.place(card.placement, screenWidth, screenHeight, new int[][]{{w,h}},
                new boolean[]{w == screenWidth}, 0, new int[][]{edge}, origin, new int[]{(int)spec.style.fields.get("edge_margin")});
        int x = origin[0][0], y = origin[0][1];
        place(card.decoration, x - edge[0], y - edge[1], w + edge[0] + edge[2], h + edge[1] + edge[3]);
        place(card, x, y, w, h);
        compact = new CardMotion.Frame(x,y,w,h,1,0);
        current = compact;
        if (expanded) {
            card.setExpanded(true,screenWidth);
            apply(restingFrame());
        }
    }

    private void apply(CardMotion.Frame frame) {
        current = frame;
        int[] e = card.insets;
        int w = Math.round(frame.width), h = Math.round(frame.height);
        bounds(card,frame.x,frame.y,w,h);
        bounds(card.decoration,frame.x-e[0],frame.y-e[1],w+e[0]+e[2],h+e[1]+e[3]);
        card.presentation(frame.full);
        card.setAlpha(frame.opacity);
        card.decoration.setAlpha(frame.opacity);
    }
    private void bounds(View v,float x,float y,int width,int height) {
        LayoutParams lp = (LayoutParams)v.getLayoutParams();
        lp.leftMargin = Math.round(x); lp.topMargin = Math.round(y);
        lp.width = Math.max(1,width); lp.height = Math.max(1,height);
        v.setLayoutParams(lp);
    }
    private void cancel() {
        if (animation != null) {
            animation.removeAllListeners(); animation.removeAllUpdateListeners(); animation.cancel();
            animation = null;
        }
        card.attentionGlow(0);
    }
    private void play(int ms, android.animation.TimeInterpolator curve, java.util.function.Consumer<Float> frame, Runnable end) {
        cancel();
        if (ms == 0 || !android.animation.ValueAnimator.areAnimatorsEnabled()) { frame.accept(1f); end.run(); return; }
        animation = android.animation.ValueAnimator.ofFloat(0,1);
        animation.setDuration(ms); animation.setInterpolator(curve);
        animation.addUpdateListener(a -> frame.accept((float)a.getAnimatedValue()));
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { end.run(); }
        });
        frame.accept(0f); animation.start();
    }
    boolean isExpanded() { return expanded; }
    private CardMotion.Frame restingFrame() {
        return expanded ? new CardMotion.Frame(0,0,screenWidth,screenHeight,1,1) : compact;
    }
    void arrive() {
        CardMotion.Frame target = restingFrame();
        String kind = (String)card.spec.style.fields.get("transition_in");
        CardMotion.Frame start = CardMotion.edge(target,kind,true,card.placement.startsWith("bottom"));
        play("none".equals(kind) ? 0 : 250,new android.view.animation.DecelerateInterpolator(),
                t -> apply(start.toward(target,t)),this::attention);
    }
    private void attention() {
        CardMotion.Frame target = restingFrame();
        String kind = (String)card.spec.style.fields.get("attention_animation");
        if ("none".equals(kind)) return;
        int ms = "double_pulse".equals(kind) ? 740 : "glow".equals(kind) ? 650 : "nudge".equals(kind) ? 360 : 450;
        play(ms,new android.view.animation.LinearInterpolator(),t -> {
            float wave = (float)Math.pow(Math.sin(Math.PI*t*("double_pulse".equals(kind) ? 2 : 1)),2);
            if ("glow".equals(kind)) card.attentionGlow(wave);
            else apply("nudge".equals(kind) ? target.transformed(target.width*.006f*wave,0,1,1)
                    : target.transformed(0,0,1+.025f*wave,1));
        },() -> apply(target));
    }
    boolean toggleSize() {
        expanded = !expanded;
        card.setExpanded(expanded,screenWidth);
        CardMotion.Frame start = current;
        CardMotion.Frame target = restingFrame();
        String kind = (String)card.spec.style.fields.get(expanded ? "transition_expand" : "transition_minimize");
        play("none".equals(kind) ? 0 : 300,new android.view.animation.AccelerateDecelerateInterpolator(),
                t -> apply(CardMotion.resize(start,target,kind,t)),() -> apply(target));
        return expanded;
    }
    void leave(Runnable end) {
        String kind = (String)card.spec.style.fields.get("transition_out");
        CardMotion.Frame start = current, target = CardMotion.edge(start,kind,false,card.placement.startsWith("bottom"));
        play("none".equals(kind) ? 0 : 180,new android.view.animation.AccelerateInterpolator(),t -> apply(start.toward(target,t)),end);
    }
    @Override protected void onDetachedFromWindow() { cancel(); super.onDetachedFromWindow(); }

    private void place(View child, int x, int y, int width, int height) {
        LayoutParams params = new LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
        params.leftMargin = x;
        params.topMargin = y;
        stage.addView(child, params);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = thumbnail ? Math.round((float) width * screenHeight / screenWidth) : MeasureSpec.getSize(heightSpec);
        stage.measure(MeasureSpec.makeMeasureSpec(screenWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(screenHeight, MeasureSpec.EXACTLY));
        stage.setScaleX((float)width / screenWidth);
        stage.setScaleY((float)height / screenHeight);
        setMeasuredDimension(width, height);
    }
}
