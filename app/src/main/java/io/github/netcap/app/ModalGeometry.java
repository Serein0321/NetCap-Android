package io.github.netcap.app;
/** 所有尺寸均为像素；矩形始终包含于已扣除系统栏、挖孔和键盘的安全区。 */
final class ModalGeometry {
    final int x, y, width, height;
    private ModalGeometry(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        width = w;
        height = h;
    }
    static ModalGeometry fit(int screenW, int screenH, int left, int top, int right, int bottom,
        int ime, int margin, int maxWidth, int requestedHeight) {
        int l = Math.min(Math.max(0, left), Math.max(0, screenW - 1));
        int t = Math.min(Math.max(0, top), Math.max(0, screenH - 1));
        int availableW = Math.max(1, screenW - l - Math.max(0, right));
        int availableH = Math.max(1, screenH - t - Math.max(Math.max(0, bottom), Math.max(0, ime)));
        int mx = Math.min(Math.max(0, margin), Math.max(0, (availableW - 1) / 2));
        int my = Math.min(Math.max(0, margin), Math.max(0, (availableH - 1) / 2));
        int w = Math.max(1, Math.min(Math.max(1, maxWidth), availableW - 2 * mx));
        int h = Math.max(1, Math.min(Math.max(1, requestedHeight), availableH - 2 * my));
        return new ModalGeometry(l + (availableW - w) / 2, t + (availableH - h) / 2, w, h);
    }
}
