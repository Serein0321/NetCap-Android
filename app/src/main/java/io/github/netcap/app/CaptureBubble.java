package io.github.netcap.app;
import android.content.Context;
import android.graphics.*;
import android.view.*;
/** Idle bubble / compact capture capsule. No animation, root calls or file reads while drawing. */
final class CaptureBubble extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ThemePalette theme;
    private BubbleState state;
    CaptureBubble(Context c) {
        super(c);
        theme = ThemeUi.palette(c);
        setFocusable(true);
        setClickable(true);
        setElevation(dp(7));
        setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16));
            }
        });
    }
    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
    void setState(BubbleState value) {
        state = value;
        setContentDescription(value.description);
        invalidate();
    }
    boolean expanded() {
        return state != null && state.expanded;
    }
    private void text(Canvas c, String value, float x, float y, float size, int color,
        Paint.Align align, float maxWidth) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTypeface(Typeface.create("sans-serif", size >= 16 ? Typeface.BOLD : Typeface.NORMAL));
        float scale = Math.min(1.25f, getResources().getConfiguration().fontScale);
        p.setTextSize(dp(size) * Math.max(1, scale));
        p.setTextAlign(align);
        float measured = p.measureText(value);
        if (measured > maxWidth && measured > 0)
            p.setTextSize(p.getTextSize() * maxWidth / measured);
        c.drawText(value, x, y, p);
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (state == null)
            return;
        p.setStyle(Paint.Style.FILL);
        p.setColor((0xf5 << 24) | (theme.modal & 0xffffff));
        canvas.drawRoundRect(
            dp(1), dp(1), getWidth() - dp(1), getHeight() - dp(1), dp(16), dp(16), p);
        int mark = state.attention || state.mode == BubbleState.Mode.ERROR ? theme.warning
            : state.mode == BubbleState.Mode.RECORDING                     ? theme.outgoing
            : state.mode == BubbleState.Mode.IDLE                          ? theme.accent
                                                                           : theme.warning;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(state.expanded ? ThemePalette.mix(theme.edge, mark, .4) : theme.edge);
        canvas.drawRoundRect(
            dp(1), dp(1), getWidth() - dp(1), getHeight() - dp(1), dp(16), dp(16), p);
        if (!state.expanded) {
            text(canvas, state.mode == BubbleState.Mode.ERROR ? "!" : "⇄", getWidth() / 2f,
                getHeight() / 2f + dp(10), 28,
                state.mode == BubbleState.Mode.ERROR ? theme.warning : theme.accent,
                Paint.Align.CENTER, getWidth() - dp(12));
            if (state.attention) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(theme.warning);
                canvas.drawCircle(getWidth() - dp(10), dp(10), dp(3), p);
            }
            return;
        }
        // Draw in a fixed design space, scaled only for exceptionally constrained safe areas.
        canvas.save();
        canvas.scale(getWidth() / dp(148), getHeight() / dp(58));
        p.setStyle(Paint.Style.FILL);
        p.setColor(mark);
        canvas.drawCircle(dp(12), dp(15), dp(3), p);
        text(canvas, state.label, dp(20), dp(19), 10, mark, Paint.Align.LEFT, dp(65));
        text(canvas, state.clock, dp(137), dp(19), 10, theme.muted, Paint.Align.RIGHT, dp(53));
        text(canvas, state.total + " 包", dp(10), dp(46), 17, theme.text, Paint.Align.LEFT, dp(79));
        text(canvas, "↑ " + state.sent, dp(137), dp(35), 10, theme.outgoing, Paint.Align.RIGHT,
            dp(45));
        text(canvas, "↓ " + state.received, dp(137), dp(48), 10, theme.accent, Paint.Align.RIGHT,
            dp(45));
        canvas.restore();
    }
}
