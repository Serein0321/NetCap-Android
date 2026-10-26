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
                a.moveTo(5, 3);
                a.lineTo(17, 3);
                a.lineTo(21, 7);
                a.lineTo(21, 19);
                a.quadTo(21, 21, 19, 21);
                a.lineTo(5, 21);
                a.quadTo(3, 21, 3, 19);
                a.lineTo(3, 5);
                a.quadTo(3, 3, 5, 3);
                a.close();
                c.drawPath(a, p);
                c.drawRoundRect(7, 3, 15, 9, 1, 1, p);
                c.drawRoundRect(7, 13, 17, 21, 1, 1, p);
                break;
            case "folder":
            case "new":
                a.moveTo(3, 7);
                a.lineTo(3, 20);
                a.lineTo(21, 20);
                a.lineTo(21, 7);
                a.lineTo(12, 7);
                a.lineTo(10, 4);
                a.lineTo(3, 4);
                a.close();
                c.drawPath(a, p);
                if (kind.equals("new")) {
                    c.drawLine(8, 13, 16, 13, p);
                    c.drawLine(12, 9, 12, 17, p);
                }
                break;
            case "up":
                c.drawLine(12, 21, 12, 4, p);
                c.drawLine(6, 10, 12, 4, p);
                c.drawLine(12, 4, 18, 10, p);
                break;
            case "import":
                c.drawLine(12, 3, 12, 16, p);
                c.drawLine(7, 11, 12, 16, p);
                c.drawLine(12, 16, 17, 11, p);
                c.drawLine(4, 16, 4, 21, p);
                c.drawLine(4, 21, 20, 21, p);
                c.drawLine(20, 21, 20, 16, p);
                break;
            case "sort":
                c.drawLine(3, 5, 14, 5, p);
                c.drawLine(3, 11, 11, 11, p);
                c.drawLine(3, 17, 8, 17, p);
                c.drawLine(18, 4, 18, 21, p);
                c.drawLine(14, 17, 18, 21, p);
                c.drawLine(18, 21, 22, 17, p);
                break;
            case "trash":
                c.drawLine(4, 6, 20, 6, p);
                c.drawLine(9, 3, 15, 3, p);
                c.drawRoundRect(6, 6, 18, 21, 2, 2, p);
                c.drawLine(10, 10, 10, 17, p);
                c.drawLine(14, 10, 14, 17, p);
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
