package io.github.netcap.app;
import android.content.Context;
import android.graphics.*;
import android.view.View;
final class ToolIcon extends View {
    final String kind;
    final Paint p = new Paint(3);
    boolean active;
    ToolIcon(Context c, String kind, String title, Runnable action) {
        super(c);
        this.kind = kind;
        setContentDescription(title);
        setTooltipText(title);
        setFocusable(true);
        setClickable(true);
        setOnClickListener(v -> action.run());
    }
    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        p.setColor(isEnabled() ? 0xffb3c5ff : 0xff626879);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.7f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.save();
        c.translate(getWidth() / 2f, getHeight() / 2f);
        float s = getResources().getDisplayMetrics().density;
        c.scale(s, s);
        c.translate(-12, -12);
        Path a = new Path();
        switch (kind) {
            case "search":
                c.drawCircle(10, 10, 6, p);
                c.drawLine(15, 15, 21, 21, p);
                break;
            case "filter":
                a.moveTo(3, 5);
                a.lineTo(21, 5);
                a.lineTo(14, 13);
                a.lineTo(14, 20);
                a.lineTo(10, 18);
                a.lineTo(10, 13);
                a.close();
                c.drawPath(a, p);
                break;
            case "save":
                a.moveTo(6, 3);
                a.lineTo(18, 3);
                a.lineTo(18, 21);
                a.lineTo(12, 17);
                a.lineTo(6, 21);
                a.close();
                c.drawPath(a, p);
                break;
            case "compare":
                c.drawRoundRect(2, 5, 10, 19, 2, 2, p);
                c.drawRoundRect(14, 5, 22, 19, 2, 2, p);
                c.drawLine(12, 3, 12, 21, p);
                break;
            case "group":
                c.drawRoundRect(9, 2, 15, 8, 1, 1, p);
                c.drawRoundRect(2, 16, 8, 22, 1, 1, p);
                c.drawRoundRect(16, 16, 22, 22, 1, 1, p);
                c.drawLine(12, 8, 12, 12, p);
                c.drawLine(5, 12, 19, 12, p);
                c.drawLine(5, 12, 5, 16, p);
                c.drawLine(19, 12, 19, 16, p);
                break;
            case "select":
                c.drawRoundRect(3, 3, 21, 21, 3, 3, p);
                c.drawLine(7, 12, 10, 15, p);
                c.drawLine(10, 15, 17, 8, p);
                break;
            case "refresh":
                c.drawArc(4, 4, 20, 20, 40, 285, false, p);
                c.drawLine(19, 3, 20, 9, p);
                c.drawLine(14, 8, 20, 9, p);
                break;
            case "more":
                p.setStyle(Paint.Style.FILL);
                for (int y = 5; y < 22; y += 7) c.drawCircle(12, y, 1.5f, p);
                break;
            case "close":
                c.drawLine(6, 6, 18, 18, p);
                c.drawLine(6, 18, 18, 6, p);
                break;
            default:
                c.drawLine(5, 12, 19, 12, p);
                c.drawLine(5, 12, 11, 6, p);
                c.drawLine(5, 12, 11, 18, p);
        }
        c.restore();
        if (active) {
            p.setColor(0xff80d5c2);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(getWidth() * .8f, getHeight() * .2f, 2.5f * s, p);
        }
    }
}
