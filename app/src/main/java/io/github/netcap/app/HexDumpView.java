package io.github.netcap.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.View;
import java.util.Locale;

/** 偏移、字节和字符按单元格绘制，不经过 TextView 自动折行。只绘制可见行。 */
final class HexDumpView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect clip = new Rect();
    private byte[] data = new byte[0];
    private int columns = 8, rowHeight;
    private float cell, inset, hexLeft, asciiLeft, baseline;

    HexDumpView(Context context) {
        super(context);
        setContentDescription("原始记录：偏移、十六进制字节、ASCII 字符");
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        cell = paint.measureText("0");
        inset = dp(6);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        rowHeight = (int) Math.ceil(metrics.descent - metrics.ascent + dp(8));
        baseline = (rowHeight - (metrics.descent - metrics.ascent)) / 2 - metrics.ascent;
    }
    private float dp(float n) {
        return n * getResources().getDisplayMetrics().density;
    }
    void setBytes(byte[] value) {
        data = value;
        requestLayout();
        invalidate();
    }
    private float requiredWidth(int n) {
        return inset * 2 + (8 + 3 * n + (n - 1) / 4 * .5f + 1 + n) * cell;
    }
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = View.MeasureSpec.getSize(widthSpec);
        if (width == 0)
            width = (int) dp(320);
        columns = requiredWidth(16) <= width ? 16 : requiredWidth(8) <= width ? 8 : 4;
        hexLeft = inset + 8 * cell;
        asciiLeft = hexLeft + (3 * columns + (columns - 1) / 4 * .5f + 1) * cell;
        int rows = (data.length + columns - 1) / columns;
        setMeasuredDimension(width, resolveSize((rows + 1) * rowHeight, heightSpec));
    }
    private float hexX(int column) {
        return hexLeft + (column * 3 + column / 4 * .5f) * cell;
    }
    private void line(Canvas canvas, String value, float x, float y, int color) {
        paint.setColor(color);
        canvas.drawText(value, x, y, paint);
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff171923);
        canvas.getClipBounds(clip);
        if (clip.top < rowHeight) {
            paint.setColor(0xff252936);
            canvas.drawRect(0, 0, getWidth(), rowHeight, paint);
            line(canvas, "偏移", inset, baseline, 0xffaeb8ce);
            for (int i = 0; i < columns; i++) {
                line(canvas, Integer.toHexString(i).toUpperCase(Locale.ROOT), hexX(i) + cell * .5f,
                    baseline, 0xffaeb8ce);
            }
            line(canvas, "字符", asciiLeft, baseline, 0xffaeb8ce);
        }
        int rows = (data.length + columns - 1) / columns;
        int first = Math.max(0, clip.top / rowHeight - 1);
        int last = Math.min(rows, (clip.bottom + rowHeight - 1) / rowHeight);
        for (int row = first; row < last; row++) {
            int offset = row * columns;
            float top = (row + 1) * rowHeight;
            if ((row & 1) != 0) {
                paint.setColor(0xff1e212d);
                canvas.drawRect(0, top, getWidth(), top + rowHeight, paint);
            }
            line(canvas, String.format(Locale.ROOT, "%06X", offset), inset, top + baseline,
                0xff929eb8);
            for (int col = 0; col < columns && offset + col < data.length; col++) {
                byte value = data[offset + col];
                line(canvas, DisplayFormat.hexByte(value), hexX(col), top + baseline, 0xffeef2fa);
                line(canvas, String.valueOf(DisplayFormat.ascii(value)), asciiLeft + col * cell,
                    top + baseline, 0xffb7c9ef);
            }
        }
        paint.setColor(0xff3b4051);
        float end = Math.min(getHeight(), (rows + 1) * rowHeight);
        canvas.drawLine(hexLeft - cell, 0, hexLeft - cell, end, paint);
        canvas.drawLine(asciiLeft - cell * .5f, 0, asciiLeft - cell * .5f, end, paint);
        for (int col = 4; col < columns; col += 4) {
            float x = hexX(col) - cell * .75f;
            canvas.drawLine(x, 0, x, end, paint);
        }
    }
}
