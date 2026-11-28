package co.tbrkr.herald;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.widget.TextView;

/** Scroll one measured paragraph through a clipped viewport, without taking remote focus. */
final class TickerText extends TextView {
    private StaticLayout line;
    private float length;
    private long start;

    private final double emPerSecond;

    TickerText(Context context, String speed) {
        super(context);
        emPerSecond = CardMotion.tickerEmPerSecond(speed);
    }

    private void measureLine() {
        if (line != null) return;
        String message = getText().toString().replace('\n',' ').replace('\r',' ');
        length = Layout.getDesiredWidth(message,getPaint());
        int width = Math.max(1,(int)Math.ceil(length));
        line = StaticLayout.Builder.obtain(message,0,message.length(),getPaint(),width)
                .setIncludePad(false).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).build();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            super.onMeasure(widthSpec,heightSpec);
            return;
        }
        measureLine();
        setMeasuredDimension(resolveSize((int)Math.ceil(length),widthSpec),
                resolveSize(line.getHeight(),heightSpec));
    }
    @Override protected void onTextChanged(CharSequence text,int start,int before,int count) {
        super.onTextChanged(text,start,before,count);
        line = null;
        this.start = 0;
    }
    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (!visible) start = 0;
    }
    @Override protected void onDraw(Canvas canvas) {
        if (!ValueAnimator.areAnimatorsEnabled()) { super.onDraw(canvas); return; }
        measureLine();
        getPaint().setColor(getCurrentTextColor());
        boolean rtl = line.getParagraphDirection(0) < 0;
        boolean moves = length > getWidth() && isShown() && getWindowVisibility() == VISIBLE;
        long now = SystemClock.uptimeMillis();
        if (!moves || start == 0) start = now;
        float gap = 3*getTextSize();
        float offset = CardMotion.tickerOffset(length,getWidth(),getTextSize(),now-start,emPerSecond);
        float first = rtl ? getWidth()-line.getWidth()+offset : -offset;
        canvas.save();
        canvas.clipRect(0,0,getWidth(),getHeight());
        canvas.translate(first,(getHeight()-line.getHeight())/2f);
        line.draw(canvas);
        if (moves) {
            canvas.translate((rtl ? -1 : 1)*(length+gap),0);
            line.draw(canvas);
            postInvalidateOnAnimation();
        }
        canvas.restore();
    }
}
