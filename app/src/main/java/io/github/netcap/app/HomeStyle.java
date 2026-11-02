package io.github.netcap.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

/** One small visual language for the launcher and its settings sheet. */
final class HomeStyle {
    final Context context;
    final ThemePalette palette;
    private final float density;
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    HomeStyle(Context context) { this(context, ThemeUi.palette(context)); }
    HomeStyle(Context context, ThemePalette palette) {
        this.context = context;
        this.palette = palette;
        density = context.getResources().getDisplayMetrics().density;
    }
    int dp(float value) { return Math.round(value * density); }
    GradientDrawable shape(int color, float radius, boolean border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (border) shape.setStroke(dp(1), palette.edge);
        return shape;
    }
    Drawable ripple(int fill, float radius, boolean border) {
        return new RippleDrawable(ColorStateList.valueOf(palette.badge),
            shape(fill, radius, border), null);
    }
    TextView text(CharSequence value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1.25f);
        return view;
    }
    TextView title(CharSequence value, float size, int color) {
        TextView view = text(value, size, color);
        view.setTypeface(medium);
        return view;
    }
    TextView mono(CharSequence value, float size, int color) {
        TextView view = text(value, size, color);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }
    Button button(String label, boolean filled) {
        Button view = new Button(context);
        view.setText(label);
        view.setTextSize(15);
        view.setAllCaps(false);
        view.setTypeface(medium);
        view.setMinHeight(dp(52));
        view.setMinimumHeight(dp(52));
        view.setPadding(dp(16), 0, dp(16), 0);
        view.setStateListAnimator(null);
        styleButton(view, filled);
        return view;
    }
    void styleButton(Button view, boolean filled) {
        view.setTextColor(filled ? palette.onAccent : palette.accent);
        view.setBackground(ripple(filled ? palette.accent : palette.active, 14, false));
    }
    TextView action(String label, int color, Runnable run) {
        TextView view = title(label, 13, color);
        view.setGravity(android.view.Gravity.CENTER);
        view.setMinHeight(dp(44));
        view.setMinWidth(dp(44));
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackground(ripple(palette.surface, 11, false));
        view.setOnClickListener(v -> run.run());
        return view;
    }
    Switch toggle() {
        Switch view = new Switch(context);
        int[][] states = {new int[] {android.R.attr.state_checked}, new int[] {}};
        view.setThumbTintList(new ColorStateList(states, new int[] {palette.accent, palette.disabled}));
        view.setTrackTintList(new ColorStateList(states, new int[] {palette.badge, palette.edge}));
        view.setMinHeight(dp(48));
        return view;
    }
    View dot(int color) {
        View view = new View(context);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        view.setBackground(dot);
        return view;
    }
    View line() {
        View view = new View(context);
        view.setBackgroundColor(palette.edge);
        return view;
    }
}
