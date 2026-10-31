package io.github.netcap.app;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;

/** Home owns connection setup; the floating workbench remains focused on capture. */
public final class MainActivity extends Activity {
    private boolean awaitingOverlay, awaitingMcpNotification, tokenVisible, detailsOpen;
    private ThemePalette palette;
    private Button launch, mcpToggle, copyConfig, detailsToggle, showToken, resetToken;
    private TextView hint, status, serviceHint, activity, localUrl, lanHint, scopeHint, configHint;
    private EditText port, token;
    private Switch lan;
    private RadioGroup configTarget;
    private RadioButton localTarget, lanTarget;
    private LinearLayout lanRows, details;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<String> hosts = Collections.emptyList();
    private String networkKey = "", lastState = "";
    private int shownPort = -1;
    private boolean shownLan;
    private final Runnable tick = new Runnable() {
        public void run() { refresh(); handler.postDelayed(this, 1500); }
    };
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int radius, boolean border) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if (border) d.setStroke(dp(1), palette.edge);
        return d;
    }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setLineSpacing(dp(3), 1); return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private void gap(LinearLayout l, int height) { l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private void divider(LinearLayout l) { View v = new View(this); v.setBackgroundColor(palette.edge); l.addView(v, new LinearLayout.LayoutParams(-1, dp(1))); }
    private Button button(String title, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(title); b.setTextSize(14); b.setAllCaps(false);
        b.setTextColor(primary ? palette.onAccent : palette.accent); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48));
        b.setPadding(dp(12), dp(10), dp(12), dp(10)); b.setStateListAnimator(null);
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(palette.badge), shape(primary ? palette.accent : palette.active, 12, false), null));
        b.setOnClickListener(v -> action.run()); return b;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = column(); c.setPadding(dp(20), dp(20), dp(20), dp(20)); c.setBackground(shape(palette.workspace, 22, true));
        parent.addView(c, new LinearLayout.LayoutParams(-1, -2)); return c;
    }
    private TextView heading(String title, int size) { TextView t = text(title, size, palette.text); t.setTypeface(null, Typeface.BOLD); return t; }
    @Override public void onCreate(Bundle state) {
        setTheme(ThemeUi.app(this)); super.onCreate(state); palette = ThemeUi.palette(this);
        awaitingOverlay = state != null && state.getBoolean("awaitingOverlay");
        awaitingMcpNotification = state != null && state.getBoolean("awaitingMcpNotification");
        detailsOpen = state != null && state.getBoolean("detailsOpen");
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(true); scroll.setBackgroundColor(palette.bg);
        final boolean wide = getResources().getConfiguration().screenWidthDp >= 700;
        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(bars.left + dp(20), bars.top + dp(20), bars.right + dp(20), bars.bottom + dp(24));
            if (in.isVisible(WindowInsets.Type.ime())) {
                v.post(() -> revealInput(scroll));
                v.postDelayed(() -> revealInput(scroll), 300);
            }
            return in;
        });
        FrameLayout frame = new FrameLayout(this); scroll.addView(frame, new ScrollView.LayoutParams(-1, -2));
        LinearLayout content = column(); content.setFocusableInTouchMode(true);
        frame.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        frame.addOnLayoutChangeListener((v, a, b, c, d, e, f, g, h) -> {
            int width = Math.min(frame.getWidth(), dp(wide ? 1040 : 720));
            if (content.getLayoutParams().width != width) { content.getLayoutParams().width = width; content.requestLayout(); }
        });
        LinearLayout brand = row();
        TextView logo = text("⇄", 26, palette.accent); logo.setGravity(Gravity.CENTER); logo.setBackground(shape(palette.active, 14, false));
        brand.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView name = heading("NetCap", 24); LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1); nameParams.leftMargin = dp(12); brand.addView(name, nameParams);
        brand.addView(text("通信工作台", 11, palette.muted)); content.addView(brand); gap(content, wide ? 16 : 24);
        LinearLayout panels = new LinearLayout(this); panels.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL); panels.setGravity(Gravity.TOP); content.addView(panels);
        LinearLayout capture = card(panels);
        if (wide) capture.setLayoutParams(new LinearLayout.LayoutParams(0, -2, .36f));
        capture.addView(heading("观察每一次通信", 21)); gap(capture, 6);
        capture.addView(text("通过悬浮工作台采集、查看和整理数据。", 13, palette.muted)); gap(capture, 16);
        launch = button("启动悬浮工具", true, this::begin); capture.addView(launch, new LinearLayout.LayoutParams(-1, -2));
        hint = text("", 12, palette.muted); gap(capture, 10); capture.addView(hint);
        if (wide) panels.addView(new View(this), new LinearLayout.LayoutParams(dp(16), 1)); else gap(panels, 16);
        LinearLayout mcp = card(panels);
        if (wide) mcp.setLayoutParams(new LinearLayout.LayoutParams(0, -2, .64f));
        LinearLayout title = row(); title.addView(heading("MCP 连接", 21), new LinearLayout.LayoutParams(0, -2, 1));
        status = text("未开启", 12, palette.muted); status.setPadding(dp(10), dp(6), dp(10), dp(6)); title.addView(status); mcp.addView(title);
        gap(mcp, 6); mcp.addView(text("让 AI 助手直接读取会话与数据包，辅助通信分析。", 13, palette.muted)); gap(mcp, 18);
        LinearLayout settings = row(), portLabel = column(); portLabel.addView(heading("监听端口", 14)); portLabel.addView(text("1024–65535", 11, palette.muted));
        settings.addView(portLabel, new LinearLayout.LayoutParams(0, -2, 1));
        port = new EditText(this); port.setId(1001); port.setTextSize(18); port.setTypeface(Typeface.MONOSPACE); port.setTextColor(palette.text);
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); port.setSingleLine(true); port.setSelectAllOnFocus(true); port.setFilters(new InputFilter[] {new InputFilter.LengthFilter(5)});
        port.setContentDescription("MCP 监听端口，1024 到 65535"); port.setText(state == null ? String.valueOf(McpSettings.port(this)) : state.getString("port", String.valueOf(McpSettings.port(this))));
        port.setPadding(dp(12), dp(10), dp(12), dp(10)); port.setBackground(shape(palette.bg, 12, true));
        settings.addView(port, new LinearLayout.LayoutParams(dp(104), -2)); mcp.addView(settings); gap(mcp, 12);
        LinearLayout scope = row(); TextView scopeLabel = heading("允许局域网连接", 14); scope.addView(scopeLabel, new LinearLayout.LayoutParams(0, -2, 1));
        lan = new Switch(this); lan.setContentDescription("允许同一局域网中的电脑连接 MCP"); lan.setMinHeight(dp(48));
        lan.setThumbTintList(new android.content.res.ColorStateList(new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}}, new int[] {palette.accent, palette.muted}));
        lan.setTrackTintList(new android.content.res.ColorStateList(new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}}, new int[] {palette.badge, palette.edge}));
        lan.setChecked(state == null ? McpSettings.lan(this) : state.getBoolean("lan", McpSettings.lan(this))); scope.addView(lan); mcp.addView(scope);
        scopeLabel.setOnClickListener(v -> { if (lan.isEnabled()) lan.setChecked(!lan.isChecked()); });
        scopeHint = text("", 12, palette.muted); mcp.addView(scopeHint);
        gap(mcp, 14); mcpToggle = button("开启 MCP 服务", true, this::toggleMcp); mcp.addView(mcpToggle, new LinearLayout.LayoutParams(-1, -2));
        serviceHint = text("", 12, palette.muted); gap(mcp, 10); mcp.addView(serviceHint);
        gap(mcp, 18); divider(mcp); gap(mcp, 16); mcp.addView(heading("连接地址", 15)); gap(mcp, 4);
        mcp.addView(text("在支持 Streamable HTTP 的客户端中添加。", 12, palette.muted)); gap(mcp, 10);
        localUrl = address(mcp, "本机 · 这台手机", "", () -> copy("MCP 本机地址", localUrl.getText().toString(), false));
        lanRows = column(); mcp.addView(lanRows); lanHint = text("", 12, palette.muted); gap(mcp, 8); mcp.addView(lanHint); gap(mcp, 14);
        configTarget = new RadioGroup(this); configTarget.setOrientation(RadioGroup.HORIZONTAL);
        localTarget = new RadioButton(this); localTarget.setId(1101); localTarget.setText("本机配置"); localTarget.setTextSize(13); localTarget.setTextColor(palette.text); localTarget.setMinHeight(dp(48));
        lanTarget = new RadioButton(this); lanTarget.setId(1102); lanTarget.setText("局域网配置"); lanTarget.setTextSize(13); lanTarget.setTextColor(palette.text); lanTarget.setMinHeight(dp(48));
        configTarget.addView(localTarget, new RadioGroup.LayoutParams(0, -2, 1)); configTarget.addView(lanTarget, new RadioGroup.LayoutParams(0, -2, 1));
        configTarget.check(state != null && state.getBoolean("lanConfig") ? 1102 : 1101); mcp.addView(configTarget);
        copyConfig = button("复制连接配置", false, this::copyConfiguration); mcp.addView(copyConfig, new LinearLayout.LayoutParams(-1, -2));
        configHint = text("", 11, palette.muted); gap(mcp, 8); mcp.addView(configHint); gap(mcp, 8);
        detailsToggle = button(detailsOpen ? "收起接入说明" : "接入说明与访问令牌", false, () -> {
            detailsOpen = !detailsOpen; details.setVisibility(detailsOpen ? View.VISIBLE : View.GONE);
            detailsToggle.setText(detailsOpen ? "收起接入说明" : "接入说明与访问令牌");
        }); mcp.addView(detailsToggle, new LinearLayout.LayoutParams(-1, -2));
        details = column(); details.setVisibility(detailsOpen ? View.VISIBLE : View.GONE); mcp.addView(details);
        gap(details, 14); details.addView(heading("访问令牌", 14)); gap(details, 4);
        details.addView(text("在客户端请求头填写 Authorization: Bearer <令牌>。复制配置已包含令牌，仅分享给可信客户端。", 12, palette.muted));
        token = new EditText(this); token.setText(McpSettings.token(this)); token.setTextSize(12); token.setTextColor(palette.text); token.setTypeface(Typeface.MONOSPACE);
        token.setKeyListener(null); token.setTextIsSelectable(true); token.setTransformationMethod(PasswordTransformationMethod.getInstance()); token.setContentDescription("MCP 访问令牌，默认隐藏");
        details.addView(token, new LinearLayout.LayoutParams(-1, -2)); gap(details, 6);
        LinearLayout tokenActions = row();
        showToken = button("显示", false, () -> { tokenVisible = !tokenVisible; token.setTransformationMethod(tokenVisible ? null : PasswordTransformationMethod.getInstance()); showToken.setText(tokenVisible ? "隐藏" : "显示"); });
        tokenActions.addView(showToken, new LinearLayout.LayoutParams(0, -2, 1));
        Button copyToken = button("复制令牌", false, () -> copy("MCP 访问令牌", McpSettings.token(this), true));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, -2, 1); actionParams.leftMargin = dp(8); tokenActions.addView(copyToken, actionParams); details.addView(tokenActions);
        gap(details, 8); resetToken = button("重新生成令牌", false, () -> {
            if (McpService.isRunning() || McpService.busy()) return;
            McpSettings.prefs(this).edit().remove("token").apply(); token.setText(McpSettings.token(this)); Toast.makeText(this, "已更新令牌，请重新复制客户端配置", Toast.LENGTH_LONG).show();
        }); details.addView(resetToken, new LinearLayout.LayoutParams(-1, -2));
        gap(details, 12); details.addView(text("1  开启 MCP，复制合适的连接配置。\n2  电脑连接请选择局域网，并与手机接入同一网络。\n3  在客户端添加服务；若只支持填写地址，请另外设置请求头。\n\n配置为通用 mcpServers JSON，具体字段以客户端为准。仅支持 IPv4 HTTP；应在可信网络使用。服务关闭后需手动开启，不随开机启动。", 12, palette.muted));
        gap(mcp, 16); divider(mcp); gap(mcp, 14); mcp.addView(text("6 项只读工具", 12, palette.accent)); gap(mcp, 4);
        mcp.addView(text("运行状态 · 会话列表 · 会话详情\n数据包筛选 · 原始载荷 · 连接汇总", 12, palette.muted));
        activity = text("尚无客户端请求", 11, palette.muted); gap(mcp, 10); mcp.addView(activity);
        gap(content, 18); TextView footer = text("采集需要 Root · MCP 可独立读取已有会话", 11, palette.muted); footer.setGravity(Gravity.CENTER); content.addView(footer);
        port.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { if (enteredPort() > 0) port.setError(null); refresh(); }
            public void afterTextChanged(Editable e) {}
        });
        lan.setOnCheckedChangeListener((b, checked) -> { if (checked && !McpService.isRunning()) configTarget.check(1102); refresh(); });
        configTarget.setOnCheckedChangeListener((group, id) -> updateConfigHint());
        setContentView(scroll); content.requestFocus(); scroll.requestApplyInsets(); refresh();
    }
    private void revealInput(ScrollView scroll) {
        View focused = getCurrentFocus();
        if (!(focused instanceof EditText)) return;
        android.graphics.Rect rect = new android.graphics.Rect();
        focused.getDrawingRect(rect); scroll.offsetDescendantRectToMyCoords(focused, rect);
        int top = scroll.getScrollY() + scroll.getPaddingTop();
        int bottom = scroll.getScrollY() + scroll.getHeight() - scroll.getPaddingBottom();
        if (rect.top < top || rect.bottom > bottom) scroll.scrollTo(0, Math.max(0, rect.top - scroll.getPaddingTop() - dp(12)));
    }
    private TextView address(LinearLayout parent, String label, String value, Runnable copy) {
        LinearLayout r = row(); r.setPadding(dp(12), dp(8), dp(4), dp(8)); r.setBackground(shape(palette.bg, 12, false));
        LinearLayout labels = column(); labels.addView(text(label, 11, palette.muted));
        TextView url = text(value, 13, palette.text); url.setTypeface(Typeface.MONOSPACE); url.setTextIsSelectable(true); labels.addView(url); r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(new ToolIcon(this, "copy", "复制" + label + "地址", copy), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(6); parent.addView(r, p); return url;
    }
    private int enteredPort() { try { int p = Integer.parseInt(port.getText().toString()); return p >= 1024 && p <= 65535 ? p : -1; } catch (Exception e) { return -1; } }
    private void refresh() {
        if (mcpToggle == null) return;
        boolean running = McpService.isRunning(), busy = McpService.busy(), locked = running || busy;
        String current = McpService.state;
        if (!current.equals(lastState)) {
            lastState = current;
            if (locked) { port.setText(String.valueOf(McpService.boundPort)); lan.setChecked(McpService.boundLan); }
        }
        int p = locked ? McpService.boundPort : enteredPort(); boolean allowLan = locked ? McpService.boundLan : lan.isChecked();
        port.setEnabled(!locked); lan.setEnabled(!locked); resetToken.setEnabled(!locked); resetToken.setAlpha(locked ? .45f : 1);
        status.setText(running ? "● 运行中" : current.equals("starting") ? "启动中" : current.equals("stopping") ? "停止中" : current.equals("error") ? "启动失败" : "未开启");
        status.setTextColor(running ? palette.outgoing : current.equals("error") ? palette.error : palette.muted); status.setBackground(shape(running ? palette.active : palette.modal, 20, false));
        mcpToggle.setText(running ? "停止 MCP 服务" : current.equals("starting") ? "正在开启…" : current.equals("stopping") ? "正在停止…" : current.equals("error") ? "重试开启 MCP" : "开启 MCP 服务");
        mcpToggle.setEnabled(!busy); mcpToggle.setAlpha(busy ? .6f : 1); mcpToggle.setTextColor(running ? palette.error : palette.onAccent);
        mcpToggle.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(palette.badge), shape(running ? palette.modal : palette.accent, 12, running), null));
        serviceHint.setTextColor(current.equals("error") ? palette.error : palette.muted);
        serviceHint.setText(current.equals("error") ? McpService.error : running ? "服务已就绪，离开此页后仍可连接。修改配置请先停止。" : busy ? "请稍候，正在更新服务状态。" : "开启后可连接；不需要先启动悬浮工具。");
        scopeHint.setText(allowLan ? "本机与同一网络内的电脑均可连接，所有请求需令牌。" : "仅这台手机可连接；电脑接入时请开启局域网。");
        localUrl.setText(p < 0 ? "请填写有效端口" : McpSettings.url("127.0.0.1", p));
        List<String> nextHosts = McpSettings.lanHosts(); String key = nextHosts.toString();
        if (!key.equals(networkKey) || p != shownPort || allowLan != shownLan) {
            hosts = nextHosts; networkKey = key; shownPort = p; shownLan = allowLan; lanRows.removeAllViews();
            if (allowLan && p > 0) for (String host : hosts) { String url = McpSettings.url(host, p); address(lanRows, "局域网 · " + host, url, () -> copy("MCP 局域网地址", url, false)); }
        }
        lanHint.setText(!allowLan ? "局域网地址未启用" : hosts.isEmpty() ? "未检测到 Wi-Fi / 以太网 IPv4 地址，请先连接网络。" : "电脑使用局域网地址；网络切换后请重新复制。");
        lanTarget.setEnabled(allowLan && !hosts.isEmpty());
        if (!lanTarget.isEnabled() && configTarget.getCheckedRadioButtonId() == 1102) configTarget.check(1101);
        copyConfig.setEnabled(p > 0); copyConfig.setAlpha(p > 0 ? 1 : .45f); updateConfigHint();
        long last = McpService.lastRequest();
        activity.setText(last == 0 ? "尚无客户端请求 · 仅开放读取与分析" : "最近请求 " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(last)) + " · 工具调用 " + McpService.calls() + " 次" + (McpService.lastTool().isEmpty() ? "" : "\n" + McpService.lastTool()));
        hint.setText(CaptureService.isRunning() ? "工具已启动 · 点击悬浮球展开工作台" : "启动后显示悬浮球 · 真实抓包需要 Root"); launch.setText(CaptureService.isRunning() ? "显示悬浮球" : "启动悬浮工具");
    }
    private void updateConfigHint() {
        if (configHint == null) return;
        boolean remote = configTarget.getCheckedRadioButtonId() == 1102;
        configHint.setText((remote ? "使用 " + (hosts.isEmpty() ? "局域网地址" : hosts.get(0)) : "使用 127.0.0.1，仅供这台手机") + " · 配置含访问令牌");
    }
    private void copyConfiguration() {
        int p = McpService.isRunning() || McpService.busy() ? McpService.boundPort : enteredPort(); if (p < 0) return;
        boolean remote = configTarget.getCheckedRadioButtonId() == 1102; if (remote && hosts.isEmpty()) return;
        try { copy("NetCap MCP 配置", McpSettings.config(McpSettings.url(remote ? hosts.get(0) : "127.0.0.1", p), McpSettings.token(this)), true); }
        catch (Exception e) { Toast.makeText(this, "配置生成失败，请重试", Toast.LENGTH_SHORT).show(); }
    }
    private void copy(String title, String value, boolean sensitive) {
        if (value.equals("请填写有效端口")) return;
        ClipData clip = ClipData.newPlainText(title, value);
        if (sensitive) { PersistableBundle extras = new PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras); }
        getSystemService(android.content.ClipboardManager.class).setPrimaryClip(clip);
        Toast.makeText(this, title + "已复制" + (!McpService.isRunning() ? "，服务尚未开启" : ""), Toast.LENGTH_SHORT).show();
    }
    private void toggleMcp() {
        if (McpService.busy()) return;
        if (McpService.isRunning()) { startService(new Intent(this, McpService.class).setAction(McpService.STOP)); return; }
        int p = enteredPort(); if (p < 0) { port.setError("请输入 1024–65535 的端口"); port.requestFocus(); return; }
        McpSettings.prefs(this).edit().putInt("port", p).putBoolean("lan", lan.isChecked()).apply();
        port.clearFocus(); getSystemService(InputMethodManager.class).hideSoftInputFromWindow(port.getWindowToken(), 0);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !McpSettings.prefs(this).getBoolean("notificationAsked", false)) {
            McpSettings.prefs(this).edit().putBoolean("notificationAsked", true).apply(); awaitingMcpNotification = true; requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 8); return;
        }
        startMcp();
    }
    private void startMcp() {
        awaitingMcpNotification = false;
        try { startForegroundService(new Intent(this, McpService.class).setAction(McpService.START)); handler.postDelayed(this::refresh, 100); }
        catch (Exception e) { McpService.state = "error"; McpService.error = "服务启动失败，请回到首页重试"; refresh(); }
    }
    @Override protected void onResume() {
        super.onResume(); if (!palette.id.equals(ThemeUi.palette(this).id)) { recreate(); return; }
        handler.removeCallbacks(tick); tick.run();
        if (awaitingOverlay && Settings.canDrawOverlays(this)) { awaitingOverlay = false; begin(); }
    }
    @Override protected void onPause() { handler.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle b) {
        b.putBoolean("awaitingOverlay", awaitingOverlay); b.putBoolean("awaitingMcpNotification", awaitingMcpNotification); b.putBoolean("detailsOpen", detailsOpen);
        b.putString("port", port.getText().toString()); b.putBoolean("lan", lan.isChecked()); b.putBoolean("lanConfig", configTarget.getCheckedRadioButtonId() == 1102); super.onSaveInstanceState(b);
    }
    private void begin() {
        if (!Settings.canDrawOverlays(this)) {
            awaitingOverlay = true; hint.setText("请允许显示在其他应用上层，授权后返回即可启动。");
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { Toast.makeText(this, "无法打开悬浮窗授权：" + e, Toast.LENGTH_LONG).show(); }
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !getPreferences(0).getBoolean("notificationAsked", false)) {
            getPreferences(0).edit().putBoolean("notificationAsked", true).apply(); requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 7); return;
        }
        startTool();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants); if (request == 7) startTool(); else if (request == 8 && awaitingMcpNotification) startMcp();
    }
    private void startTool() {
        if (!Settings.canDrawOverlays(this)) { begin(); return; }
        try { startForegroundService(new Intent(this, CaptureService.class).setAction(CaptureService.TOOL_START)); moveTaskToBack(true); }
        catch (Exception e) { hint.setText("启动失败：" + e.getMessage()); }
    }
}
