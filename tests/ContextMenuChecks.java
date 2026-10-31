package io.github.netcap.app;

/** 主机验证指针菜单在横竖屏、安全区域和不同菜单尺寸下不会越界。 */
public final class ContextMenuChecks {
    static int checks;
    static void need(boolean ok, String message) {
        checks++;
        if (!ok)
            throw new AssertionError(message);
    }
    public static void main(String[] args) {
        ContextMenuGeometry right = ContextMenuGeometry.fit(
            1080, 2400, 0, 80, 0, 100, 16, 20, 100, 900, 448, 960);
        need(right.x == 120, "有空间时位于指针右侧");
        ContextMenuGeometry left = ContextMenuGeometry.fit(
            1080, 2400, 0, 80, 0, 100, 16, 20, 1000, 900, 448, 960);
        need(left.x + left.width == 980, "右边缘时转到指针左侧");
        ContextMenuGeometry above = ContextMenuGeometry.fit(
            400, 900, 0, 40, 0, 40, 8, 10, 200, 800, 224, 480);
        need(above.y + above.height <= 790, "侧边不足且底部按压时向上放置");
        ContextMenuGeometry below = ContextMenuGeometry.fit(
            400, 900, 0, 40, 0, 40, 8, 10, 200, 80, 224, 480);
        need(below.y >= 90, "顶部按压时向下放置");
        java.util.Random random = new java.util.Random(31);
        for (int i = 0; i < 8000; i++) {
            int width = 1 + random.nextInt(3000), height = 1 + random.nextInt(3000);
            int l = random.nextInt(width), r = random.nextInt(width - l);
            int t = random.nextInt(height), b = random.nextInt(height - t);
            int margin = random.nextInt(40);
            ModalGeometry safe = ModalGeometry.fit(width, height, l, t, r, b, 0, margin,
                width, height);
            int wantedW = 1 + random.nextInt(1200), wantedH = 1 + random.nextInt(2400);
            ContextMenuGeometry menu = ContextMenuGeometry.fit(width, height, l, t, r, b,
                margin, random.nextInt(30), random.nextInt(width + 400) - 200,
                random.nextInt(height + 400) - 200, wantedW, wantedH);
            need(menu.width > 0 && menu.height > 0, "始终具有有效尺寸");
            need(menu.x >= safe.x && menu.y >= safe.y, "左上不越过系统安全区域");
            need(menu.x + menu.width <= safe.x + safe.width
                    && menu.y + menu.height <= safe.y + safe.height,
                "右下不越过系统安全区域");
            need(menu.width == Math.min(wantedW, safe.width)
                    && menu.height == Math.min(wantedH, safe.height),
                "只有空间不足时才收缩菜单");
        }
        System.out.println("Context menu checks passed: " + checks);
    }
}
