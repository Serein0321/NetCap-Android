package io.github.netcap.app;
/** Density/font aware gallery columns; uses the measured space, not display orientation. */
final class StyleLayout {
    static int columns(int pixels, float density, float fontScale) {
        double min =
            136 * Math.max(.1f, density) * Math.max(1, Float.isFinite(fontScale) ? fontScale : 1);
        return Math.max(1, Math.min(4, (int) (Math.max(0, pixels) / min)));
    }
}
