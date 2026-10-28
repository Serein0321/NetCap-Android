package io.github.netcap.app;
import android.content.Context;
import android.view.*;
/**
 * Measures real cards on the first pass; never bootstraps children from an empty layout callback.
 */
final class ThemeGrid extends ViewGroup {
    private int columns = 1, slot, gap;
    private int[] heights = new int[0];
    ThemeGrid(Context c) {
        super(c);
        gap = Math.round(6 * getResources().getDisplayMetrics().density);
    }
    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(-1, -2);
    }
    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED)
            w = Math.round(320 * getResources().getDisplayMetrics().density);
        columns = StyleLayout.columns(w, getResources().getDisplayMetrics().density,
            getResources().getConfiguration().fontScale);
        slot = Math.max(1, (w - gap * (columns - 1)) / columns);
        int rows = (getChildCount() + columns - 1) / columns;
        heights = new int[rows];
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(slot, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            heights[i / columns] = Math.max(heights[i / columns], child.getMeasuredHeight());
        }
        int h = 0;
        for (int value : heights) h += value + gap;
        setMeasuredDimension(resolveSize(w, ws), resolveSize(Math.max(0, h - gap), hs));
    }
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int y = 0;
        for (int row = 0; row < heights.length; row++) {
            for (int col = 0; col < columns; col++) {
                int i = row * columns + col;
                if (i >= getChildCount())
                    break;
                int x = col * (slot + gap);
                getChildAt(i).layout(x, y, x + slot, y + heights[row]);
            }
            y += heights[row] + gap;
        }
    }
}
