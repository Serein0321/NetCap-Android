package io.github.netcap.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Quiet launch surface. Two jobs only: start the floating workbench, and expose a read-only MCP
 * endpoint with its two usable addresses. Everything optional lives behind the gear.
 */
public final class MainActivity extends Activity {
    private boolean awaitingOverlay;
    private ThemePalette palette;
    private HomeStyle style;
    private Button launch, mcpButton;
    private Status captureStatus, mcpStatus;
    private TextView captureHint, mcpHint, lanHint, authNote, activity;
    private LinearLayout mcpIdle, mcpRunning, lanRows;
    private Address localAddress;
    private ToolIcon settingsIcon;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> hosts = new ArrayList<>();
    private long hostsReadAt;
    private String lanSignature = "", lastMcpState = "", captureError = "";
    private final Runnable tick = new Runnable() {
        public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    public void onCreate(Bundle state) {
        setTheme(ThemeUi.app(this));
        super.onCreate(state);
        palette = ThemeUi.palette(this);
        style = new HomeStyle(this);
        awaitingOverlay = state != null && state.getBoolean("awaitingOverlay");
        build();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(palette.bg);
        final int side = style.dp(24);
        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars =
                in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left + side, bars.top + style.dp(18),
                bars.right + side, bars.bottom + style.dp(28));
            return in;
        });

        FrameLayout frame = new FrameLayout(this);
        scroll.addView(frame, new ScrollView.LayoutParams(-1, -2));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        frame.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        frame.addOnLayoutChangeListener((v, a, b, c, d, e, f, g, h) -> {
            int width = Math.min(frame.getWidth(), style.dp(560));
            if (content.getLayoutParams().width != width) {
                content.getLayoutParams().width = width;
                content.requestLayout();
            }
        });

        content.addView(header());
        gap(content, 26);
        content.addView(panel());
        gap(content, 20);

        TextView footer = style.text("抓包数据保存在本机", 11, palette.disabled);
        footer.setGravity(Gravity.CENTER);
        content.addView(footer);

        setContentView(scroll);
        scroll.requestApplyInsets();
        refresh();
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(new BrandMark(this), new LinearLayout.LayoutParams(style.dp(46), style.dp(46)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = style.title("NetCap", 24, palette.text);
        name.setLetterSpacing(.02f);
        names.addView(name);
        names.addView(style.text("本机通信观察", 12, palette.muted));
        LinearLayout.LayoutParams namesParams = new LinearLayout.LayoutParams(0, -2, 1);
        namesParams.setMarginStart(style.dp(14));
        row.addView(names, namesParams);
        return row;
    }

    private View panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(style.dp(20), style.dp(20), style.dp(20), style.dp(20));
        panel.setBackground(style.shape(palette.workspace, 24, true));

        LinearLayout captureHead = new LinearLayout(this);
        captureHead.setOrientation(LinearLayout.HORIZONTAL);
        captureHead.setGravity(Gravity.CENTER_VERTICAL);
        captureHead.addView(style.title("悬浮工作台", 15, palette.text),
            new LinearLayout.LayoutParams(0, -2, 1));
        captureStatus = new Status();
        captureStatus.view.setId(Ids.CAPTURE_STATUS);
        captureHead.addView(captureStatus.view);
        panel.addView(captureHead);

        TextView captureDesc = style.text("在目标应用上方查看与记录通信", 12, palette.muted);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.topMargin = style.dp(6);
        panel.addView(captureDesc, descParams);

        launch = style.button("启动", true);
        launch.setId(Ids.LAUNCH);
        launch.setOnClickListener(v -> {
            if (CaptureService.isRunning()) openWorkspace();
            else begin();
        });
        LinearLayout.LayoutParams launchParams = new LinearLayout.LayoutParams(-1, style.dp(52));
        launchParams.topMargin = style.dp(16);
        panel.addView(launch, launchParams);

        captureHint = style.text("", 11.5f, palette.muted);
        captureHint.setId(Ids.CAPTURE_HINT);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = style.dp(10);
        panel.addView(captureHint, hintParams);

        View divider = style.line();
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, style.dp(1));
        dividerParams.topMargin = style.dp(18);
        dividerParams.bottomMargin = style.dp(18);
        panel.addView(divider, dividerParams);

        LinearLayout mcpHead = new LinearLayout(this);
        mcpHead.setOrientation(LinearLayout.HORIZONTAL);
        mcpHead.setGravity(Gravity.CENTER_VERTICAL);
        mcpHead.addView(style.title("MCP 连接", 15, palette.text),
            new LinearLayout.LayoutParams(0, -2, 1));
        mcpStatus = new Status();
        mcpStatus.text.setId(Ids.MCP_STATUS);
        mcpHead.addView(mcpStatus.view);
        settingsIcon = new ToolIcon(this, "settings", "MCP 设置", this::openSettings);
        settingsIcon.setId(Ids.MCP_SETTINGS);
        LinearLayout.LayoutParams gearParams = new LinearLayout.LayoutParams(style.dp(44), style.dp(44));
        gearParams.setMarginStart(style.dp(4));
        mcpHead.addView(settingsIcon, gearParams);
        panel.addView(mcpHead);

        TextView mcpDesc = style.text("让 AI 助手只读访问本机会话", 12, palette.muted);
        LinearLayout.LayoutParams mcpDescParams = new LinearLayout.LayoutParams(-1, -2);
        mcpDescParams.topMargin = style.dp(6);
        panel.addView(mcpDesc, mcpDescParams);

        mcpIdle = new LinearLayout(this);
        mcpIdle.setOrientation(LinearLayout.VERTICAL);
        mcpButton = style.button("开启 MCP", false);
        mcpButton.setId(Ids.MCP_TOGGLE);
        mcpButton.setOnClickListener(v -> toggleMcp());
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-1, style.dp(52));
        toggleParams.topMargin = style.dp(14);
        mcpIdle.addView(mcpButton, toggleParams);
        mcpHint = style.text("开启后显示本机与局域网地址", 11.5f, palette.muted);
        mcpHint.setId(Ids.MCP_HINT);
        LinearLayout.LayoutParams mcpHintParams = new LinearLayout.LayoutParams(-1, -2);
        mcpHintParams.topMargin = style.dp(10);
        mcpIdle.addView(mcpHint, mcpHintParams);
        panel.addView(mcpIdle);

        mcpRunning = new LinearLayout(this);
        mcpRunning.setOrientation(LinearLayout.VERTICAL);
        mcpRunning.setId(Ids.MCP_RUNNING);
        mcpRunning.setVisibility(View.GONE);
        LinearLayout.LayoutParams runningParams = new LinearLayout.LayoutParams(-1, -2);
        runningParams.topMargin = style.dp(14);
        panel.addView(mcpRunning, runningParams);

        localAddress = new Address("本机", "本机地址");
        localAddress.value.setId(Ids.MCP_LOCAL);
        localAddress.view.setId(Ids.MCP_LOCAL_ROW);
        mcpRunning.addView(localAddress.view);

        lanRows = new LinearLayout(this);
        lanRows.setOrientation(LinearLayout.VERTICAL);
        mcpRunning.addView(lanRows);

        lanHint = style.text("", 11, palette.muted);
        lanHint.setId(Ids.MCP_LAN_HINT);
        LinearLayout.LayoutParams lanHintParams = new LinearLayout.LayoutParams(-1, -2);
        lanHintParams.topMargin = style.dp(10);
        mcpRunning.addView(lanHint, lanHintParams);

        authNote = style.text("", 11, palette.warning);
        authNote.setId(Ids.MCP_AUTH_NOTE);
        LinearLayout.LayoutParams authParams = new LinearLayout.LayoutParams(-1, -2);
        authParams.topMargin = style.dp(8);
        mcpRunning.addView(authNote, authParams);

        activity = style.text("", 10.5f, palette.disabled);
        activity.setId(Ids.MCP_ACTIVITY);
        LinearLayout.LayoutParams activityParams = new LinearLayout.LayoutParams(-1, -2);
        activityParams.topMargin = style.dp(8);
        mcpRunning.addView(activity, activityParams);

        TextView stop = style.action("停止 MCP", palette.muted, this::stopMcp);
        stop.setId(Ids.MCP_STOP);
        LinearLayout stopRow = new LinearLayout(this);
        stopRow.setOrientation(LinearLayout.HORIZONTAL);
        stopRow.setGravity(Gravity.START);
        stopRow.addView(stop);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-2, -2);
        stopParams.topMargin = style.dp(8);
        stopParams.setMarginStart(-style.dp(12));
        mcpRunning.addView(stopRow, stopParams);
        return panel;
    }

    private void gap(LinearLayout parent, int height) {
        View view = new View(this);
        parent.addView(view, new LinearLayout.LayoutParams(1, style.dp(height)));
    }

    private void refresh() {
        if (launch == null) return;
        if (!palette.id.equals(ThemeUi.palette(this).id)) {
            recreate();
            return;
        }
        refreshCapture();
        refreshMcp();
    }

    private void refreshCapture() {
        boolean running = CaptureService.isRunning();
        boolean stopping = CaptureService.stopping;
        boolean active = CaptureService.active;
        if (stopping) {
            captureStatus.set("正在停止", palette.warning);
            captureHint.setText("正在停止并保存本次记录…");
            captureHint.setTextColor(palette.muted);
            launch.setText("请稍候");
            launch.setEnabled(false);
            launch.setAlpha(.55f);
            style.styleButton(launch, false);
        } else if (running) {
            captureStatus.set(active ? "抓包中" : "已启动", active ? palette.outgoing : palette.accent);
            captureHint.setText(active ? "正在抓包 · 点击悬浮球查看实时数据"
                                        : "工具已就绪 · 点击悬浮球展开工作台");
            captureHint.setTextColor(palette.muted);
            launch.setText("打开工作台");
            launch.setEnabled(true);
            launch.setAlpha(1f);
            style.styleButton(launch, false);
        } else {
            captureStatus.set("未启动", palette.muted);
            if (awaitingOverlay && !Settings.canDrawOverlays(this)) {
                captureHint.setText("请允许显示在其他应用上层，授权后返回即可启动");
                captureHint.setTextColor(palette.warning);
            } else if (!captureError.isEmpty()) {
                captureHint.setText(captureError);
                captureHint.setTextColor(palette.error);
            } else {
                captureHint.setText("启动后显示悬浮球 · 真实抓包需要 Root");
                captureHint.setTextColor(palette.muted);
            }
            launch.setText("启动");
            launch.setEnabled(true);
            launch.setAlpha(1f);
            style.styleButton(launch, true);
        }
    }

    private void refreshMcp() {
        String state = McpService.state;
        boolean running = McpService.isRunning();
        boolean busy = McpService.busy();

        if (state.equals("error")) mcpStatus.set("启动失败", palette.error);
        else if (running) mcpStatus.set("运行中", palette.outgoing);
        else if (state.equals("starting")) mcpStatus.set("启动中", palette.accent);
        else if (state.equals("stopping")) mcpStatus.set("停止中", palette.accent);
        else mcpStatus.set("未开启", palette.muted);

        mcpIdle.setVisibility(running ? View.GONE : View.VISIBLE);
        mcpRunning.setVisibility(running ? View.VISIBLE : View.GONE);

        if (!running) {
            if (state.equals("starting")) {
                mcpButton.setText("正在开启…");
                mcpButton.setEnabled(false);
                mcpButton.setAlpha(.55f);
            } else if (state.equals("stopping")) {
                mcpButton.setText("正在停止…");
                mcpButton.setEnabled(false);
                mcpButton.setAlpha(.55f);
            } else if (state.equals("error")) {
                mcpButton.setText("重试");
                mcpButton.setEnabled(true);
                mcpButton.setAlpha(1f);
            } else {
                mcpButton.setText("开启 MCP");
                mcpButton.setEnabled(true);
                mcpButton.setAlpha(1f);
            }
            if (state.equals("error")) {
                mcpHint.setText(McpService.error);
                mcpHint.setTextColor(palette.error);
            } else {
                mcpHint.setText("开启后显示本机与局域网地址");
                mcpHint.setTextColor(palette.muted);
            }
        } else {
            int port = McpService.boundPort;
            localAddress.set(McpSettings.url("127.0.0.1", port));
            refreshLanRows(port);
            if (!McpService.boundLan) {
                lanHint.setText("局域网访问已关闭 · 可在设置中开启");
            } else if (hosts.isEmpty()) {
                lanHint.setText("未检测到局域网地址 · 连接 Wi‑Fi 后自动刷新");
            } else {
                lanHint.setText("本机地址供手机客户端使用；电脑请用局域网地址。");
            }
            if (McpService.boundAuth) {
                authNote.setText("已启用访问令牌 · 在设置中查看");
                authNote.setTextColor(palette.muted);
                authNote.setVisibility(View.VISIBLE);
            } else if (McpService.boundLan) {
                authNote.setText("未启用访问令牌 · 同网设备可直接读取");
                authNote.setTextColor(palette.warning);
                authNote.setVisibility(View.VISIBLE);
            } else {
                authNote.setVisibility(View.GONE);
            }
            long last = McpService.lastRequest();
            if (last > 0) {
                String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(last));
                activity.setText("最近请求 " + time + " · 工具调用 " + McpService.calls() + " 次");
                activity.setVisibility(View.VISIBLE);
            } else {
                activity.setVisibility(View.GONE);
            }
            if (!lastMcpState.equals("running")) animateIn(mcpRunning);
        }
        lastMcpState = state;
    }

    private void refreshLanRows(int port) {
        long now = SystemClock.elapsedRealtime();
        if (now - hostsReadAt > 4000) {
            hosts.clear();
            hosts.addAll(McpSettings.lanHosts());
            hostsReadAt = now;
            lanSignature = "";
        }
        String signature = port + "|" + McpService.boundLan + "|" + hosts;
        if (signature.equals(lanSignature)) return;
        lanSignature = signature;
        lanRows.removeAllViews();
        if (!McpService.boundLan) return;
        for (String host : hosts) {
            Address row = new Address("局域网", "局域网地址");
            row.view.setId(Ids.MCP_LAN_ROW);
            row.set(McpSettings.url(host, port));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = style.dp(8);
            lanRows.addView(row.view, params);
        }
    }

    private void animateIn(View view) {
        view.setAlpha(0f);
        view.setTranslationY(style.dp(6));
        view.animate().alpha(1f).translationY(0f).setDuration(180).start();
    }

    private void toggleMcp() {
        if (McpService.busy()) return;
        mcpButton.setEnabled(false);
        mcpButton.setAlpha(.55f);
        mcpHint.setText("正在开启…");
        mcpHint.setTextColor(palette.muted);
        try {
            startForegroundService(new Intent(this, McpService.class).setAction(McpService.START));
            handler.postDelayed(this::refresh, 120);
        } catch (Exception e) {
            mcpButton.setEnabled(true);
            mcpButton.setAlpha(1f);
            mcpHint.setText("无法启动 MCP 服务，请重试");
            mcpHint.setTextColor(palette.error);
            Toast.makeText(this, "启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopMcp() {
        try {
            startService(new Intent(this, McpService.class).setAction(McpService.STOP));
            handler.postDelayed(this::refresh, 80);
        } catch (Exception e) {
            Toast.makeText(this, "停止失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openSettings() {
        startActivity(new Intent(this, McpSettingsActivity.class));
    }

    private void openWorkspace() {
        try {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.SHOW_WORKSPACE));
            moveTaskToBack(true);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开工作台：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void copyText(String value, String message) {
        ClipboardManager manager = getSystemService(ClipboardManager.class);
        manager.setPrimaryClip(ClipData.newPlainText(message, value));
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!palette.id.equals(ThemeUi.palette(this).id)) {
            recreate();
            return;
        }
        handler.removeCallbacks(tick);
        tick.run();
        if (awaitingOverlay && Settings.canDrawOverlays(this)) {
            awaitingOverlay = false;
            begin();
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("awaitingOverlay", awaitingOverlay);
        super.onSaveInstanceState(state);
    }

    private void begin() {
        captureError = "";
        if (!Settings.canDrawOverlays(this)) {
            awaitingOverlay = true;
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开悬浮窗授权：" + e, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            && !getPreferences(0).getBoolean("notificationAsked", false)) {
            getPreferences(0).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 7);
            return;
        }
        startTool();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 7) startTool();
    }

    private void startTool() {
        if (!Settings.canDrawOverlays(this)) {
            begin();
            return;
        }
        try {
            startForegroundService(
                new Intent(this, CaptureService.class).setAction(CaptureService.TOOL_START));
            awaitingOverlay = false;
            moveTaskToBack(true);
        } catch (Exception e) {
            captureError = "启动失败：" + e.getMessage();
            refreshCapture();
        }
    }

    /** Small status lamp: one dot and one word, aligned to the section header. */
    private final class Status {
        final LinearLayout view;
        final TextView text;
        private final View dot;
        Status() {
            view = new LinearLayout(MainActivity.this);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            dot = style.dot(palette.muted);
            view.addView(dot, new LinearLayout.LayoutParams(style.dp(7), style.dp(7)));
            text = style.text("", 12, palette.muted);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMarginStart(style.dp(6));
            view.addView(text, params);
        }
        void set(String label, int color) {
            text.setText(label);
            text.setTextColor(color);
            ((GradientDrawable) dot.getBackground()).setColor(color);
        }
    }

    /** One copyable endpoint. Entire row copies; the icon is the affordance. */
    private final class Address {
        final LinearLayout view;
        final TextView value;
        private final String copyTitle;
        private String url = "";
        Address(String label, String copyTitle) {
            this.copyTitle = copyTitle;
            view = new LinearLayout(MainActivity.this);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPaddingRelative(style.dp(14), style.dp(10), style.dp(6), style.dp(10));
            view.setBackground(style.ripple(palette.bg, 14, true));
            view.setClickable(true);
            view.setFocusable(true);
            view.setContentDescription("复制" + copyTitle);

            LinearLayout labels = new LinearLayout(MainActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name = style.text(label, 10, palette.muted);
            name.setLetterSpacing(.08f);
            labels.addView(name);
            value = style.mono("", 12, palette.text);
            value.setSingleLine(true);
            value.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            labels.addView(value);
            view.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

            ToolIcon copy = new ToolIcon(MainActivity.this, "copy", "复制" + copyTitle, this::copy);
            view.addView(copy, new LinearLayout.LayoutParams(style.dp(44), style.dp(44)));
            view.setOnClickListener(v -> copy());
        }
        void set(String address) {
            url = address;
            value.setText(address);
        }
        void copy() {
            if (!url.isEmpty()) copyText(url, copyTitle + "已复制");
        }
    }

    /** The launcher mark: exchange arrows drawn on a quiet accent tile. */
    private final class BrandMark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        BrandMark(android.content.Context context) {
            super(context);
            setContentDescription("NetCap");
        }
        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(palette.active);
            canvas.drawRoundRect(0, 0, w, h, style.dp(15), style.dp(15), paint);

            float unit = Math.min(w, h) * .50f / 24f;
            canvas.save();
            canvas.translate((w - 24 * unit) / 2f, (h - 24 * unit) / 2f);
            canvas.scale(unit, unit);
            paint.setColor(palette.accent);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.1f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawLine(4, 7, 16, 7, paint);
            canvas.drawLine(8, 13, 20, 13, paint);
            canvas.drawLine(4, 19, 16, 19, paint);
            paint.setStyle(Paint.Style.FILL);
            Path arrow = new Path();
            arrow.moveTo(16, 4.5f); arrow.lineTo(21, 7); arrow.lineTo(16, 9.5f); arrow.close();
            canvas.drawPath(arrow, paint);
            arrow.reset();
            arrow.moveTo(8, 10.5f); arrow.lineTo(3, 13); arrow.lineTo(8, 15.5f); arrow.close();
            canvas.drawPath(arrow, paint);
            arrow.reset();
            arrow.moveTo(16, 16.5f); arrow.lineTo(21, 19); arrow.lineTo(16, 21.5f); arrow.close();
            canvas.drawPath(arrow, paint);
            canvas.restore();
        }
    }
}
