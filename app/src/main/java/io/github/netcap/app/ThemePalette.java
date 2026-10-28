package io.github.netcap.app;

/** Semantic colors shared by every app-owned surface. No Android dependency. */
final class ThemePalette {
    static final ThemePalette[] ALL = {
        new ThemePalette("indigo", "鸢尾蓝", "冷静 · 清晰", 0xff10121a, 0xff20232e, 0xff282a35,
            0xffb3c5ff, 0xff353e55),
        new ThemePalette("jade", "雾松绿", "自然 · 沉静", 0xff0b1715, 0xff1a2c28, 0xff243a33,
            0xff9de2c6, 0xff354e45),
        new ThemePalette("ocean", "深海青", "通透 · 专注", 0xff0b161f, 0xff1b2b39, 0xff233848,
            0xff8ed9ee, 0xff354e61),
        new ThemePalette("plum", "暮色紫", "柔和 · 克制", 0xff19131e, 0xff2c2333, 0xff382e42,
            0xffddbbf4, 0xff50405e),
        new ThemePalette("amber", "日落琥珀", "温暖 · 安定", 0xff1a1510, 0xff2b251b, 0xff3a3022,
            0xfff3d291, 0xff514632),
        new ThemePalette("rose", "蔷薇粉", "轻柔 · 明快", 0xff1d1118, 0xff30202a, 0xff402c38,
            0xfff3b8d1, 0xff5b3d50),
        new ThemePalette("copper", "赤陶橙", "质朴 · 温润", 0xff1c1311, 0xff30241f, 0xff40302a,
            0xfff2b59b, 0xff5c443b),
        new ThemePalette("lime", "青柠绿", "清新 · 利落", 0xff14190d, 0xff252d1b, 0xff303b24,
            0xffc8df9b, 0xff485538),
        new ThemePalette("slate", "石墨灰", "简洁 · 中性", 0xff121518, 0xff23282d, 0xff2d343a,
            0xffd0d9e2, 0xff444f5a),
        new ThemePalette("midnight", "午夜蓝", "深邃 · 专注", 0xff0b1020, 0xff181f35, 0xff232c46,
            0xff9fb8f7, 0xff364365),
        new ThemePalette("lavender", "薰衣草", "舒缓 · 清雅", 0xff171423, 0xff282339, 0xff352e4a,
            0xffc8bcf3, 0xff4b4269),
        new ThemePalette("coffee", "摩卡棕", "沉稳 · 柔和", 0xff191411, 0xff2c2420, 0xff3a302a,
            0xffddc5ae, 0xff51453c)};
    final String id, name, mood;
    final int bg, workspace, modal, accent, edge, surface, active, badge;
    final int text = 0xfff2f4fa, muted = 0xffbbc3d2, warning = 0xffffd18d, error = 0xffffa3aa,
              outgoing = 0xff9ee2ca, onAccent = 0xff102326, disabled = 0xff768398;
    ThemePalette(String id, String name, String mood, int bg, int workspace, int modal, int accent,
        int edge) {
        this.id = id;
        this.name = name;
        this.mood = mood;
        this.bg = bg;
        this.workspace = workspace;
        this.modal = modal;
        this.accent = accent;
        this.edge = edge;
        surface = mix(bg, workspace, .10);
        active = mix(bg, accent, .18);
        badge = mix(bg, accent, .27);
    }
    static ThemePalette find(String id) {
        for (ThemePalette t : ALL)
            if (t.id.equals(id))
                return t;
        return ALL[0];
    }
    static int mix(int a, int b, double amount) {
        int c = 0xff000000;
        for (int shift = 0; shift <= 16; shift += 8)
            c |= (int) Math.round(
                     ((a >>> shift) & 255) * (1 - amount) + ((b >>> shift) & 255) * amount)
                << shift;
        return c;
    }
}
