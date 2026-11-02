package io.github.netcap.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Everything optional about MCP, out of the way until the owner asks for it. */
public final class McpSettingsActivity extends Activity {
    private ThemePalette palette;
    private HomeStyle style;
    private Switch lanSwitch, authSwitch;
    private EditText port;
    private LinearLayout tokenBlock;
    private TextView tokenValue, note;
    private TextView revealAction, resetAction;
    private boolean tokenVisible, locked;
    private ScrollView scroll;

    private int dp(float value) { return style.dp(value); }

    @Override
    public void onCreate(Bundle state) {
        setTheme(ThemeUi.app(this));
        super.onCreate(state);
        palette = ThemeUi.palette(this);
        style = new HomeStyle(this, palette);
        locked = McpService.isRunning() || McpService.busy();
        tokenVisible = state != null && state.getBoolean("tokenVisible");
        build();
    }

    private void build() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(palette.bg);
        final int side = dp(24);
        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars =
                in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = in.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left + side, bars.top + dp(10),
                bars.right + side, bars.bottom + dp(24));
            if (ime.bottom > 0) {
                v.post(() -> revealInput());
                v.postDelayed(this::revealInput, 280);
            }
            return in;
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ToolIcon back = new ToolIcon(this, "back", "返回", this::finishWithValidation);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        backParams.setMarginStart(-dp(10));
        bar.addView(back, backParams);
        TextView title = style.title("MCP 设置", 20, palette.text);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMarginStart(dp(6));
        bar.addView(title, titleParams);
        content.addView(bar);

        TextView intro = style.text("只影响 MCP 服务，不改变抓包。", 12, palette.muted);
        LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(-1, -2);
        introParams.topMargin = dp(4);
        introParams.setMarginStart(dp(2));
        content.addView(intro, introParams);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(20));
        panel.setBackground(style.shape(palette.workspace, 22, true));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, -2);
        panelParams.topMargin = dp(20);
        content.addView(panel, panelParams);

        note = style.text("", 11.5f, palette.warning);
        note.setId(Ids.SETTINGS_NOTE);
        if (locked) {
            note.setText(McpService.busy() ? "正在更新 MCP 状态，请稍候…" : "服务运行中 · 停止 MCP 后可修改设置");
            note.setTextColor(McpService.busy() ? palette.accent : palette.warning);
            panel.addView(note);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(1, dp(12));
            panel.addView(new View(this), gap);
        } else {
            note.setVisibility(View.GONE);
        }

        lanSwitch = style.toggle();
        lanSwitch.setId(Ids.SETTINGS_LAN);
        lanSwitch.setContentDescription("允许局域网访问");
        lanSwitch.setChecked(McpSettings.lan(this));
        panel.addView(switchRow("局域网访问", "允许同一网络内的电脑连接", lanSwitch,
            locked ? null : () -> lanSwitch.setChecked(!lanSwitch.isChecked())));
        if (!locked) lanSwitch.setOnCheckedChangeListener((button, checked) ->
            McpSettings.prefs(this).edit().putBoolean("lan", checked).apply());

        divider(panel);

        authSwitch = style.toggle();
        authSwitch.setId(Ids.SETTINGS_AUTH);
        authSwitch.setContentDescription("要求访问令牌");
        authSwitch.setChecked(McpSettings.auth(this));
        panel.addView(switchRow("访问令牌", "客户端需携带 Bearer 令牌", authSwitch,
            locked ? null : () -> authSwitch.setChecked(!authSwitch.isChecked())));
        if (!locked) authSwitch.setOnCheckedChangeListener((button, checked) -> {
            McpSettings.prefs(this).edit().putBoolean("auth", checked).apply();
            if (checked) McpSettings.token(this);
            updateTokenBlock();
        });

        tokenBlock = new LinearLayout(this);
        tokenBlock.setId(Ids.SETTINGS_TOKEN_BLOCK);
        tokenBlock.setOrientation(LinearLayout.VERTICAL);
        tokenBlock.setPadding(dp(14), dp(12), dp(14), dp(12));
        tokenBlock.setBackground(style.shape(palette.bg, 14, true));
        LinearLayout.LayoutParams tokenParams = new LinearLayout.LayoutParams(-1, -2);
        tokenParams.topMargin = dp(12);
        panel.addView(tokenBlock, tokenParams);

        tokenValue = style.mono("", 11, palette.text);
        tokenValue.setId(Ids.SETTINGS_TOKEN);
        tokenValue.setTypeface(Typeface.MONOSPACE);
        tokenValue.setTextIsSelectable(false);
        tokenBlock.addView(tokenValue);

        LinearLayout tokenActions = new LinearLayout(this);
        tokenActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tokenActionParams = new LinearLayout.LayoutParams(-1, -2);
        tokenActionParams.topMargin = dp(8);
        tokenBlock.addView(tokenActions, tokenActionParams);
        revealAction = style.action("显示", palette.accent, this::toggleToken);
        revealAction.setId(Ids.SETTINGS_REVEAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, -2, 1);
        tokenActions.addView(revealAction, actionParams);
        TextView copy = style.action("复制", palette.accent, this::copyToken);
        copy.setId(Ids.SETTINGS_COPY_TOKEN);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.setMarginStart(dp(6));
        tokenActions.addView(copy, copyParams);
        resetAction = style.action("重新生成", palette.accent, this::resetToken);
        resetAction.setId(Ids.SETTINGS_RESET_TOKEN);
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(0, -2, 1);
        resetParams.setMarginStart(dp(6));
        tokenActions.addView(resetAction, resetParams);

        TextView headerHint = style.text("请求头：Authorization: Bearer <令牌>", 10.5f, palette.muted);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2);
        headerParams.topMargin = dp(8);
        tokenBlock.addView(headerHint, headerParams);

        divider(panel);

        LinearLayout portRow = new LinearLayout(this);
        portRow.setOrientation(LinearLayout.HORIZONTAL);
        portRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout portLabels = new LinearLayout(this);
        portLabels.setOrientation(LinearLayout.VERTICAL);
        portLabels.addView(style.title("监听端口", 14, palette.text));
        portLabels.addView(style.text("默认 2845 · 1024–65535", 11, palette.muted));
        portRow.addView(portLabels, new LinearLayout.LayoutParams(0, -2, 1));

        port = new EditText(this);
        port.setId(Ids.SETTINGS_PORT);
        port.setText(String.valueOf(McpSettings.port(this)));
        port.setTextSize(16);
        port.setTypeface(Typeface.MONOSPACE);
        port.setTextColor(palette.text);
        port.setHintTextColor(palette.disabled);
        port.setGravity(Gravity.CENTER);
        port.setSingleLine(true);
        port.setSelectAllOnFocus(true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setFilters(new InputFilter[] {new InputFilter.LengthFilter(5)});
        port.setContentDescription("MCP 监听端口，1024 到 65535");
        port.setPadding(dp(12), dp(10), dp(12), dp(10));
        port.setBackground(style.shape(palette.bg, 12, true));
        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(dp(112), -2);
        portParams.setMarginStart(dp(12));
        portRow.addView(port, portParams);
        panel.addView(portRow);

        TextView portHint = style.text("修改后，下次开启 MCP 生效。", 10.5f, palette.muted);
        LinearLayout.LayoutParams portHintParams = new LinearLayout.LayoutParams(-1, -2);
        portHintParams.topMargin = dp(8);
        panel.addView(portHint, portHintParams);

        updateTokenBlock();

        if (locked) {
            lanSwitch.setEnabled(false);
            authSwitch.setEnabled(false);
            port.setEnabled(false);
            port.setAlpha(.55f);
            resetAction.setEnabled(false);
            resetAction.setAlpha(.45f);
        }

        TextView done = style.button("完成", true);
        done.setId(Ids.SETTINGS_DONE);
        done.setOnClickListener(v -> finishWithValidation());
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(-1, dp(52));
        doneParams.topMargin = dp(20);
        content.addView(done, doneParams);

        TextView footer = style.text("MCP 仅提供只读访问", 11, palette.disabled);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2);
        footerParams.topMargin = dp(16);
        content.addView(footer, footerParams);

        setContentView(scroll);
        scroll.requestApplyInsets();
    }

    private View switchRow(String title, String description, Switch toggle, Runnable rowAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        row.setMinimumHeight(dp(58));
        row.setClickable(rowAction != null);
        row.setFocusable(false);
        if (rowAction != null) row.setOnClickListener(v -> rowAction.run());

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(style.title(title, 14, palette.text));
        labels.addView(style.text(description, 11, palette.muted));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        toggle.setClickable(true);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-2, -2);
        toggleParams.setMarginStart(dp(12));
        row.addView(toggle, toggleParams);
        return row;
    }

    private void divider(LinearLayout parent) {
        View line = style.line();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(14);
        params.bottomMargin = dp(14);
        parent.addView(line, params);
    }

    private void updateTokenBlock() {
        boolean on = authSwitch != null && authSwitch.isChecked();
        tokenBlock.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;
        String token = McpSettings.token(this);
        tokenValue.setText(token);
        tokenValue.setTransformationMethod(
            tokenVisible ? null : PasswordTransformationMethod.getInstance());
        revealAction.setText(tokenVisible ? "隐藏" : "显示");
        if (resetAction != null) {
            resetAction.setEnabled(!locked);
            resetAction.setAlpha(locked ? 0.45f : 1f);
        }
    }

    private void toggleToken() {
        tokenVisible = !tokenVisible;
        updateTokenBlock();
    }

    private void copyToken() {
        String token = McpSettings.token(this);
        ClipData clip = ClipData.newPlainText("NetCap MCP 访问令牌", token);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        Toast.makeText(this, "访问令牌已复制", Toast.LENGTH_SHORT).show();
    }

    private void resetToken() {
        if (locked) return;
        McpSettings.resetToken(this);
        tokenVisible = false;
        updateTokenBlock();
        Toast.makeText(this, "已生成新令牌，旧配置将失效", Toast.LENGTH_LONG).show();
    }

    private boolean savePort() {
        if (locked) return true;
        String value = port.getText().toString().trim();
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            number = -1;
        }
        if (!McpSettings.validPort(number)) {
            port.setError("请输入 1024–65535 的端口");
            port.requestFocus();
            return false;
        }
        port.setError(null);
        McpSettings.prefs(this).edit().putInt("port", number).apply();
        return true;
    }

    private void finishWithValidation() {
        if (!savePort()) return;
        getSystemService(InputMethodManager.class)
            .hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        finish();
    }

    private void revealInput() {
        View focused = getCurrentFocus();
        if (!(focused instanceof EditText)) return;
        android.graphics.Rect rect = new android.graphics.Rect();
        focused.getDrawingRect(rect);
        scroll.offsetDescendantRectToMyCoords(focused, rect);
        int top = scroll.getScrollY() + scroll.getPaddingTop();
        int bottom = scroll.getScrollY() + scroll.getHeight() - scroll.getPaddingBottom();
        if (rect.top < top || rect.bottom > bottom) {
            scroll.scrollTo(0, Math.max(0, rect.top - scroll.getPaddingTop() - dp(16)));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("tokenVisible", tokenVisible);
        super.onSaveInstanceState(state);
    }
}
