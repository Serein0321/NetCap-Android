package io.github.netcap.app;
import android.content.Context;
final class ThemeUi {
    static ThemePalette palette(Context c) {
        return ThemePalette.find(
            c.getSharedPreferences("settings", 0).getString("theme", "indigo"));
    }
    static int overlay(Context c) {
        switch (palette(c).id) {
            case "jade":
                return R.style.OverlayJadeTheme;
            case "ocean":
                return R.style.OverlayOceanTheme;
            case "plum":
                return R.style.OverlayPlumTheme;
            case "amber":
                return R.style.OverlayAmberTheme;
            case "rose":
                return R.style.OverlayRoseTheme;
            case "copper":
                return R.style.OverlayCopperTheme;
            case "lime":
                return R.style.OverlayLimeTheme;
            case "slate":
                return R.style.OverlaySlateTheme;
            case "midnight":
                return R.style.OverlayMidnightTheme;
            case "lavender":
                return R.style.OverlayLavenderTheme;
            case "coffee":
                return R.style.OverlayCoffeeTheme;
            default:
                return R.style.OverlayTheme;
        }
    }
    static int app(Context c) {
        switch (palette(c).id) {
            case "jade":
                return R.style.AppJadeTheme;
            case "ocean":
                return R.style.AppOceanTheme;
            case "plum":
                return R.style.AppPlumTheme;
            case "amber":
                return R.style.AppAmberTheme;
            case "rose":
                return R.style.AppRoseTheme;
            case "copper":
                return R.style.AppCopperTheme;
            case "lime":
                return R.style.AppLimeTheme;
            case "slate":
                return R.style.AppSlateTheme;
            case "midnight":
                return R.style.AppMidnightTheme;
            case "lavender":
                return R.style.AppLavenderTheme;
            case "coffee":
                return R.style.AppCoffeeTheme;
            default:
                return R.style.AppTheme;
        }
    }
}
