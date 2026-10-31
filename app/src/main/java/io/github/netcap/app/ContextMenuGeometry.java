package io.github.netcap.app;

/** 指针定位菜单；尺寸均为像素，优先显示在按压点右侧或左侧，并限制在安全区内。 */
final class ContextMenuGeometry {
    final int x, y, width, height;
    private ContextMenuGeometry(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }
    static ContextMenuGeometry fit(int screenW, int screenH, int left, int top, int right,
        int bottom, int margin, int gap, int pressX, int pressY, int wantedW, int wantedH) {
        ModalGeometry safe = ModalGeometry.fit(screenW, screenH, left, top, right, bottom, 0,
            margin, screenW, screenH);
        int w = Math.max(1, Math.min(wantedW, safe.width));
        int h = Math.max(1, Math.min(wantedH, safe.height));
        int px = clamp(pressX, safe.x, safe.x + safe.width);
        int py = clamp(pressY, safe.y, safe.y + safe.height);
        int spaceRight = safe.x + safe.width - px - gap;
        int spaceLeft = px - gap - safe.x;
        int x = spaceRight >= w || spaceRight >= spaceLeft ? px + gap : px - gap - w;
        // 侧边空间不足时放在指针上方/下方，尽量避免遮住按住的条目。
        int y = Math.max(spaceRight, spaceLeft) >= w ? py - h / 3
            : (safe.y + safe.height - py - gap >= h || py - safe.y < safe.height / 2
                      ? py + gap : py - gap - h);
        return new ContextMenuGeometry(clamp(x, safe.x, safe.x + safe.width - w),
            clamp(y, safe.y, safe.y + safe.height - h), w, h);
    }
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
