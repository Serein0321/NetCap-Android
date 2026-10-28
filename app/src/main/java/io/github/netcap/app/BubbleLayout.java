package io.github.netcap.app;
/** Pure pixel placement. Expanded capsule opens toward the screen interior from a 52dp anchor. */
final class BubbleLayout {
    final int x, y, width, height;
    final boolean right;
    private BubbleLayout(int x, int y, int w, int h, boolean right) {
        this.x = x;
        this.y = y;
        width = w;
        height = h;
        this.right = right;
    }
    static BubbleLayout fit(int screenW, int screenH, int left, int top, int insetRight, int bottom,
        int anchorX, int anchorY, int idle, int desiredW, int desiredH, int margin) {
        int l = Math.max(0, Math.min(left, Math.max(0, screenW - 1))),
            t = Math.max(0, Math.min(top, Math.max(0, screenH - 1)));
        int availableW = Math.max(1, screenW - l - Math.max(0, insetRight)),
            availableH = Math.max(1, screenH - t - Math.max(0, bottom));
        int mx = Math.min(Math.max(0, margin), (availableW - 1) / 2),
            my = Math.min(Math.max(0, margin), (availableH - 1) / 2);
        int w = Math.max(1, Math.min(desiredW, availableW - 2 * mx)),
            h = Math.max(1, Math.min(desiredH, availableH - 2 * my));
        boolean right = anchorX + (long) idle / 2 > l + availableW / 2;
        int x = right ? anchorX + idle - w : anchorX, y = anchorY + (idle - h) / 2;
        x = Math.max(l + mx, Math.min(x, l + availableW - mx - w));
        y = Math.max(t + my, Math.min(y, t + availableH - my - h));
        return new BubbleLayout(x, y, w, h, right);
    }
    static BubbleLayout drag(int sw, int sh, int left, int top, int right, int bottom, int x, int y,
        int width, int height, int margin) {
        int minX = Math.max(0, left) + margin, minY = Math.max(0, top) + margin;
        int maxX = Math.max(minX, sw - Math.max(0, right) - width - margin);
        int maxY = Math.max(minY, sh - Math.max(0, bottom) - height - margin);
        return new BubbleLayout(Math.max(minX, Math.min(x, maxX)),
            Math.max(minY, Math.min(y, maxY)), width, height, false);
    }
    static int anchorX(int x, int width, int idle, int screenW, int left, int insetRight) {
        long center = left + (Math.max(1, screenW - left - Math.max(0, insetRight))) / 2L;
        return x + width / 2L > center ? x + width - idle : x;
    }
}
