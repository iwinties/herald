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

        CardView card = new CardView(context, new CardBoard.Entry(spec, 0, 0, 0),
                screenWidth, screenHeight, "General", null, () -> {});
        boolean full = "fullscreen".equals(card.placement);
        boolean wrap = !full && "pill".equals(card.layout) && !"full".equals(spec.style.fields.get("size"));
        card.measure(MeasureSpec.makeMeasureSpec(card.width, wrap ? MeasureSpec.AT_MOST : MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(screenHeight, full ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST));
        int w = card.getMeasuredWidth(), h = card.getMeasuredHeight();
        int[][] origin = new int[1][2];
        int[] edge = card.insets;
        CardFrame.place(card.placement, screenWidth, screenHeight, new int[][]{{w,h}},
                new boolean[]{w == screenWidth}, 0, new int[][]{edge}, origin);
        int x = origin[0][0], y = origin[0][1];
        place(card.decoration, x - edge[0], y - edge[1], w + edge[0] + edge[2], h + edge[1] + edge[3]);
        place(card, x, y, w, h);
    }

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
