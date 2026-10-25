package io.github.netcap.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;

/** 仅负责启动和系统权限；所有业务操作在 Service 的系统悬浮窗口里。 */
public final class MainActivity extends Activity {
    private boolean awaitingOverlay;
    private Button launch;
    private TextView hint;
    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        awaitingOverlay = state != null && state.getBoolean("awaitingOverlay");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(32), dp(32), dp(32), dp(32));
        root.setBackgroundColor(0xff14151d);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars =
                in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(
                bars.left + dp(32), bars.top + dp(24), bars.right + dp(32), bars.bottom + dp(24));
            return in;
        });
        TextView symbol = new TextView(this);
        symbol.setText("⇄");
        symbol.setTextSize(56);
        symbol.setTextColor(0xffb3c5ff);
        symbol.setGravity(Gravity.CENTER);
        root.addView(symbol);
        TextView title = new TextView(this);
        title.setText("内核抓包工具");
        title.setTextSize(26);
        title.setTextColor(0xfff1f2fa);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView description = new TextView(this);
        description.setText("在当前应用上方，观察每一次通信");
        description.setTextSize(13);
        description.setTextColor(0xffaeb3c8);
        description.setGravity(Gravity.CENTER);
        description.setPadding(0, dp(12), 0, dp(36));
        root.addView(description);
        launch = new Button(this);
        launch.setText("启动");
        launch.setTextSize(18);
        launch.setAllCaps(false);
        launch.setTextColor(0xff202b4b);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xffb3c5ff);
        bg.setCornerRadius(dp(16));
        launch.setBackground(bg);
        root.addView(launch, new LinearLayout.LayoutParams(-1, dp(58)));
        launch.setOnClickListener(v -> begin());
        hint = new TextView(this);
        hint.setTextSize(12);
        hint.setTextColor(0xffaeb3c8);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(20), 0, 0);
        root.addView(hint);
        setContentView(root);
        root.requestApplyInsets();
    }
    @Override
    protected void onResume() {
        super.onResume();
        hint.setText(CaptureService.isRunning() ? "工具已启动 · 点击悬浮球展开工作台"
                                                : "启动后显示悬浮球 · 真实抓包需要 Root");
        launch.setText(CaptureService.isRunning() ? "显示悬浮球" : "启动");
        if (awaitingOverlay && Settings.canDrawOverlays(this)) {
            awaitingOverlay = false;
            begin();
        }
    }
    @Override
    protected void onSaveInstanceState(Bundle b) {
        b.putBoolean("awaitingOverlay", awaitingOverlay);
        super.onSaveInstanceState(b);
    }
    private void begin() {
        if (!Settings.canDrawOverlays(this)) {
            awaitingOverlay = true;
            hint.setText("请允许显示在其他应用上层，授权后返回即可启动。");
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
        if (request == 7)
            startTool();
    }
    private void startTool() {
        if (!Settings.canDrawOverlays(this)) {
            begin();
            return;
        }
        try {
            startForegroundService(
                new Intent(this, CaptureService.class).setAction(CaptureService.TOOL_START));
            moveTaskToBack(true);
        } catch (Exception e) {
            hint.setText("启动失败：" + e.getMessage());
        }
    }
}
