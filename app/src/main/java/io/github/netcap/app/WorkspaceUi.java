package io.github.netcap.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.Process;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Service 所有的真正系统悬浮工作台；不启动 Activity，不持有启动页。 */
final class WorkspaceUi extends ContextThemeWrapper {
    private static final int BG = 0xff10121a, SURFACE = 0xf211131b, EDGE = 0xff353947,
                             TEXT = 0xfff1f2fa, MUTED = 0xffb3b8cc, ACCENT = 0xffb3c5ff,
                             AMBER = 0xffffcc80, RED = 0xffff8e92;
    private final CaptureService service;
    private final Dialog workspace;
    private final android.window.OnBackInvokedCallback workspaceBack;
    private boolean visible, disposed;
    private int rootRequests;
    private boolean resumeAfterRoot;
    private final List<Dialog> rootHidden = new ArrayList<>();
    private final List<Runnable> afterRoot = new ArrayList<>();
    private TextView processLabel, processMemory;
    private ImageView processIcon;
    private String iconPackage;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private LinearLayout shell, nav;
    private FrameLayout body;
    private int tab;
    private SharedPreferences prefs;
    private TextView status, stats, scope;
    private Button captureButton;
    private File selectedSession;
    private String search = "";
    private int searchProto = 0;
    private long cursor = -1;
    private File clearedSession;
    private long clearedAfterSequence;
    private boolean queryBusy = false, liveRefresh = true;
    private volatile int generation = 0;
    private ListView packetList;
    private final List<PacketStore.Record> records = new ArrayList<>();
    private final List<Dialog> openDialogs = new ArrayList<>();
    private final Set<Dialog> fixedDialogs = new HashSet<>();
    private BaseAdapter packetAdapter;
    private PacketStore.Record pinned;
    private byte[] pinnedPayload;
    private long lastRefresh = 0;
    private final Runnable tick = new Runnable() {
        public void run() {
            updateStatus();
            if (tab == 1 && CaptureService.active && liveRefresh && search.isEmpty() && !queryBusy
                && System.currentTimeMillis() - lastRefresh > 1500) {
                if (CaptureService.activeSession != null)
                    selectedSession = CaptureService.activeSession;
                loadPackets(true);
            }
            ui.postDelayed(this, 600);
        }
    };
    WorkspaceUi(CaptureService owner) {
        super(owner.createWindowContext(
                  owner.getSystemService(android.hardware.display.DisplayManager.class)
                      .getDisplay(Display.DEFAULT_DISPLAY),
                  WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null),
            R.style.OverlayTheme);
        service = owner;
        prefs = getSharedPreferences("settings", 0);
        prefs.edit().remove("screenshots").apply();
        if (!prefs.getBoolean("refinedDark", false))
            prefs.edit().putInt("opacity", 86).putBoolean("refinedDark", true).apply();
        workspace = new Dialog(this);
        workspaceBack = () -> back(workspace, () -> navigateBack());
        workspace.requestWindowFeature(Window.FEATURE_NO_TITLE);
        configureWindow(workspace.getWindow(), false);
        workspace.setCancelable(false);
        workspace.setOnDismissListener(d -> {
            visible = false;
            ui.removeCallbacks(tick);
            service.workspaceDismissed();
        });
    }
    boolean isVisible() {
        return visible && workspace.isShowing();
    }
    void show() {
        if (rootRequests > 0) {
            toast("请先完成 Root 管理器授权，工作台随后恢复");
            return;
        }
        if (disposed || isVisible())
            return;
        visible = true;
        build();
        workspace.show();
        getWindow().setLayout(-1, -1);
        getWindow().getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, workspaceBack);
        ui.removeCallbacks(tick);
        ui.post(tick);
    }
    void hide() {
        resumeAfterRoot = false;
        rootHidden.clear();
        visible = false;
        generation++;
        queryBusy = false;
        ui.removeCallbacks(tick);
        getWindow().getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(workspaceBack);
        for (Dialog d : new ArrayList<>(openDialogs))
            d.dismiss();
        openDialogs.clear();
        fixedDialogs.clear();
        getSystemService(InputMethodManager.class)
            .hideSoftInputFromWindow(workspace.getWindow().getDecorView().getWindowToken(), 0);
        workspace.dismiss();
    }
    void destroy() {
        disposed = true;
        hide();
        ui.removeCallbacksAndMessages(null);
        afterRoot.clear();
        io.shutdownNow();
    }
    void pauseForRoot() {
        if (++rootRequests != 1 || disposed)
            return;
        resumeAfterRoot = isVisible();
        if (!resumeAfterRoot)
            return;
        rootHidden.clear();
        for (Dialog d : openDialogs)
            if (d.isShowing()) {
                rootHidden.add(d);
                getSystemService(InputMethodManager.class)
                    .hideSoftInputFromWindow(d.getWindow().getDecorView().getWindowToken(), 0);
                d.hide();
            }
        workspace.hide();
        visible = false;
        ui.removeCallbacks(tick);
    }
    void resumeFromRoot() {
        if (rootRequests > 0)
            rootRequests--;
        if (rootRequests != 0 || disposed)
            return;
        if (resumeAfterRoot) {
            resumeAfterRoot = false;
            workspace.show();
            visible = true;
            for (Dialog d : rootHidden)
                if (openDialogs.contains(d))
                    d.show();
            ui.removeCallbacks(tick);
            ui.post(tick);
        }
        rootHidden.clear();
        List<Runnable> pending = new ArrayList<>(afterRoot);
        afterRoot.clear();
        for (Runnable r : pending)
            r.run();
    }
    void resized() {
        if (isVisible()) {
            getWindow().setLayout(-1, -1);
            shell.requestApplyInsets();
            for (Dialog d : new ArrayList<>(openDialogs))
                if (d.isShowing())
                    fitDialog(d);
        }
    }
    private Window getWindow() {
        return workspace.getWindow();
    }
    private WindowManager getWindowManager() {
        return getSystemService(WindowManager.class);
    }
    private void configureWindow(Window w, boolean modal) {
        if (w == null)
            return;
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        // 这两个标志会让系统键盘失焦或落在窗口下方，所有输入层都必须清除。
        w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        w.setSoftInputMode((modal ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                  : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (modal) {
            w.setBackgroundDrawable(background(0xff282a35, 22));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(.45f);
        } else {
            w.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0);
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            w.setDecorFitsSystemWindows(false);
            WindowManager.LayoutParams layout = w.getAttributes();
            layout.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            layout.setFitInsetsTypes(0);
            layout.gravity = Gravity.TOP | Gravity.LEFT;
            layout.x = layout.y = 0;
            w.setAttributes(layout);
        }
        w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void back(Dialog d, Runnable next) {
        View decor = d.getWindow().getDecorView();
        WindowInsets in = decor.getRootWindowInsets();
        if (in != null && in.isVisible(WindowInsets.Type.ime())) {
            d.getWindow().getInsetsController().hide(WindowInsets.Type.ime());
            return;
        }
        next.run();
    }
    private int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }
    private void post(Runnable r) {
        ui.post(() -> {
            if (disposed)
                return;
            if (rootRequests > 0)
                afterRoot.add(r);
            else
                r.run();
        });
    }
    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
    private TextView text(String s, int size, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setLineSpacing(dp(3), 1);
        return v;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), EDGE);
        return d;
    }
    private LinearLayout col() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }
    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }
    private void gap(LinearLayout box, int height) {
        View v = new View(this);
        box.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
    }
    private Button button(String name, boolean primary, Runnable action) {
        Button v = new Button(this);
        v.setText(name);
        v.setTextSize(14);
        v.setAllCaps(false);
        v.setTextColor(primary ? 0xff062e2d : TEXT);
        v.setBackground(background(primary ? ACCENT : BG, 10));
        v.setMinHeight(dp(48));
        v.setPadding(dp(14), dp(5), dp(14), dp(5));
        v.setOnClickListener(w -> action.run());
        return v;
    }
    private void fullButton(LinearLayout box, String name, boolean primary, Runnable action) {
        Button b = button(name, primary, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(10);
        box.addView(b, lp);
    }
    private LinearLayout card(LinearLayout box, String title) {
        LinearLayout c = col();
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(background(SURFACE, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        box.addView(c, lp);
        if (!title.isEmpty()) {
            TextView t = text(title, 17, TEXT);
            t.setTypeface(null, Typeface.BOLD);
            c.addView(t);
        }
        return c;
    }
    private ScrollView scroll() {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setClipToPadding(false);
        return s;
    }
    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setSingleLine();
        e.setTextSize(14);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setHint(hint);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        e.setBackground(background(BG, 12));
        return e;
    }
    private File baseDir() {
        return new File(prefs.getString(
            "directory", new File(getExternalFilesDir(null), "captures").getAbsolutePath()));
    }
    void allowScreenCapture() {
        List<Dialog> windows = new ArrayList<>(openDialogs);
        windows.add(workspace);
        for (Dialog d : windows)
            d.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void applyBackdrop() {
        shell.setBackgroundColor((prefs.getInt("opacity", 86) * 255 / 100 << 24) | 0x1b1d25);
    }
    private void build() {
        shell = col();
        applyBackdrop();
        shell.setClickable(true); // 半透明只影响显示；空白位置也消费触摸，不传给游戏。
        shell.setFocusableInTouchMode(true);
        shell.setPadding(dp(8), dp(8), dp(8), dp(8));
        workspace.setContentView(shell);
        shell.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets a =
                in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int keyboard = in.getInsets(WindowInsets.Type.ime()).bottom;
            v.setPadding(a.left + dp(8), a.top + dp(6), a.right + dp(8),
                Math.max(a.bottom, keyboard) + dp(6));
            return in;
        });
        shell.requestApplyInsets();
        LinearLayout header = row();
        processIcon = new ImageView(this);
        iconPackage = null;
        processIcon.setPadding(dp(4), dp(4), dp(4), dp(4));
        processIcon.setBackground(background(0x503e4357, 12));
        processIcon.setContentDescription("选择应用进程");
        processIcon.setOnClickListener(v -> processDialog());
        header.addView(processIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        nav = row();
        header.addView(nav, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button collapse = button("×", false, () -> collapse());
        collapse.setTextSize(24);
        collapse.setPadding(0, 0, 0, 0);
        collapse.setContentDescription("收起为悬浮球，抓包继续");
        header.addView(collapse, new LinearLayout.LayoutParams(dp(44), dp(44)));
        shell.addView(header);
        gap(shell, 4);
        processLabel = text("", 12, TEXT);
        processLabel.setSingleLine(true);
        processLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        processLabel.setPadding(dp(6), dp(6), dp(6), dp(6));
        processLabel.setOnClickListener(v -> processDialog());
        LinearLayout processRow = row();
        processRow.addView(processLabel, new LinearLayout.LayoutParams(0, -2, 1));
        processMemory = text("", 12, ACCENT);
        processMemory.setPadding(dp(8), 0, dp(6), 0);
        processMemory.setOnClickListener(v -> processDialog());
        processRow.addView(processMemory);
        shell.addView(processRow);
        View divider = new View(this);
        divider.setBackgroundColor(0x807e879e);
        shell.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        gap(shell, 8);
        body = new FrameLayout(this);
        shell.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        allowScreenCapture();
        showTab(tab);
        shell.requestFocus();
    }
    private void showTab(int index) {
        tab = index;
        generation++;
        queryBusy = false;
        status = stats = scope = null;
        captureButton = null;
        body.removeAllViews();
        nav.removeAllViews();
        String[] labels = {"◉\n抓包", "≋\n数据", "▤\n历史", "⚙\n设置"};
        for (int i = 0; i < labels.length; i++) {
            final int page = i;
            TextView t = text(labels[i], 12, i == tab ? ACCENT : MUTED);
            t.setLineSpacing(0, 1);
            t.setGravity(Gravity.CENTER);
            t.setTypeface(null, Typeface.BOLD);
            if (i == tab)
                t.setBackground(background(0x404d5e91, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            nav.addView(t, lp);
            t.setOnClickListener(v -> showTab(page));
        }
        if (tab == 0)
            home();
        else if (tab == 1)
            packetsPage();
        else if (tab == 2)
            history();
        else
            settings();
        updateStatus();
    }
    private void home() {
        ScrollView sc = scroll();
        LinearLayout content = col();
        sc.addView(content);
        body.addView(sc);
        LinearLayout session = card(content, "本次记录");
        status = text("准备就绪", 16, ACCENT);
        stats = text("0 包  ·  0 B", 24, TEXT);
        scope = text("", 13, MUTED);
        gap(session, 12);
        session.addView(status);
        gap(session, 12);
        session.addView(stats);
        gap(session, 10);
        session.addView(scope);
        gap(session, 14);
        captureButton = button("开始抓包", true, () -> {
            if (CaptureService.active)
                stopCapture();
            else
                startCapture();
        });
        session.addView(captureButton, new LinearLayout.LayoutParams(-1, dp(54)));
        updateStatus();
    }
    private void updateStatus() {
        JSONObject j = CaptureService.snapshot();
        if (processLabel != null)
            processLabel.setText("⌁  " + prefs.getString("label", "点击选择应用进程")
                + (prefs.getInt("uid", -1) < 0
                        ? " · 整机 IP"
                        : " · PID " + (prefs.getInt("pid", -1) > 0 ? prefs.getInt("pid", -1) : "—")
                            + " · UID " + prefs.getInt("uid", -1)));
        if (processMemory != null)
            processMemory.setText(prefs.getInt("uid", -1) >= 0 && prefs.contains("rss")
                    ? DisplayFormat.memory(prefs.getLong("rss", 0))
                    : "");
        String pkg = prefs.getInt("uid", -1) < 0 ? "" : prefs.getString("package", "");
        if (processIcon != null && !pkg.equals(iconPackage)) {
            iconPackage = pkg;
            try {
                processIcon.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
            } catch (Exception e) {
                processIcon.setImageDrawable(getDrawable(R.drawable.ic_launcher));
            }
        }
        if (tab != 0 || status == null)
            return;
        String state = j.optString("state");
        String label = CaptureService.active
            ? (CaptureService.stopping           ? "正在排空并保存…"
                      : state.equals("starting") ? "等待 Root 授权…"
                                                 : "正在记录")
            : state.equals("saved")                                ? "文件已保存"
            : state.equals("attention")                            ? "已保存 · 有完整性提示"
            : state.equals("error") || state.equals("interrupted") ? "记录需要检查"
                                                                   : "准备就绪";
        status.setText(label);
        status.setTextColor(state.equals("error") || state.equals("interrupted") ? RED : ACCENT);
        stats.setText(
            j.optLong("written") + " 包  ·  " + PacketStore.formatBytes(j.optLong("bytes")));
        String detail = "发出 " + j.optLong("up") + "   收到 " + j.optLong("down") + "\n内核丢包 "
            + (j.optBoolean("kernelStatsAvailable") ? String.valueOf(j.optLong("kernelDrops"))
                                                    : "未采样")
            + " · 队列丢弃 " + j.optLong("queueDrops") + " · 截断 " + j.optLong("truncated");
        detail += "\n观测 " + j.optLong("received") + " · 纳入 " + j.optLong("accepted")
            + " · 已保存 " + j.optLong("written") + "\n筛除 " + j.optLong("filtered")
            + " · 未归属 " + j.optLong("unresolved");
        if (!j.optString("error").isEmpty())
            detail += "\n" + j.optString("error");
        scope.setText(detail);
        captureButton.setText(CaptureService.active
                ? (CaptureService.stopping ? "正在保存…" : "停止并保存")
                : "开始抓包");
        captureButton.setEnabled(!CaptureService.stopping);
    }
    private void startCapture() {
        if (prefs.getInt("uid", -1) >= 0) {
            String pkg = prefs.getString("package", "");
            try {
                if (pkg.isEmpty()
                    || getPackageManager().getApplicationInfo(pkg, 0).uid
                        != prefs.getInt("uid", -1))
                    throw new PackageManager.NameNotFoundException();
            } catch (Exception e) {
                toast("目标应用的身份已变化，或选中了独立隔离进程。请重新选择当前应用的主进程。");
                processDialog();
                return;
            }
        }
        if (prefs.getInt("uid", -1) < 0 && !prefs.getBoolean("scopeChosen", false)) {
            alert()
                .setTitle("先确定抓取范围")
                .setMessage("你还没有选择应用。整机模式会记录所选接口上的所有 IP 流量。")
                .setPositiveButton("选择应用", (d, w) -> processDialog())
                .setNeutralButton("使用整机模式",
                    (d, w) -> {
                        prefs.edit().putBoolean("scopeChosen", true).apply();
                        startCapture();
                    })
                .setNegativeButton("取消", null)
                .show();
            return;
        }
        Intent i = new Intent(this, CaptureService.class)
                       .setAction(CaptureService.START)
                       .putExtra("path", baseDir().getAbsolutePath())
                       .putExtra("label", prefs.getString("label", "整机流量"))
                       .putExtra("uid", prefs.getInt("uid", -1))
                       .putExtra("protocol", prefs.getInt("protocol", 0))
                       .putExtra("iface", prefs.getInt("iface", 0));
        try {
            startForegroundService(i);
            ui.postDelayed(() -> updateStatus(), 200);
        } catch (Exception e) {
            error("启动失败", e);
        }
    }
    private void stopCapture() {
        startService(new Intent(this, CaptureService.class).setAction(CaptureService.STOP));
        updateStatus();
    }
    private void collapse() {
        service.collapseWorkspace();
    }
    private Dialog dialog(String title, LinearLayout content) {
        Dialog d = track(new Dialog(this));
        d.setTitle(title);
        d.setContentView(content);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(background(0xff282a35, 22));
        }
        return d;
    }
    // 所有子弹窗在 show 前设为悬浮窗口，保持模态触摸与系统 IME 的正常关系。
    private <T extends Dialog> T track(T d) {
        if (!openDialogs.contains(d)) {
            configureWindow(d.getWindow(), true);
            openDialogs.add(d);
            d.setOnDismissListener(x -> {
                openDialogs.remove(d);
                fixedDialogs.remove(d);
            });
            View decor = d.getWindow().getDecorView();
            android.window.OnBackInvokedCallback callback = () -> back(d, () -> {
                if (!fixedDialogs.contains(d))
                    d.cancel();
            });
            decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View v) {
                    ui.post(() -> {
                        if (v.isAttachedToWindow())
                            d.getWindow()
                                .getOnBackInvokedDispatcher()
                                .registerOnBackInvokedCallback(
                                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                                    callback);
                    });
                }
                public void onViewDetachedFromWindow(View v) {
                    d.getWindow().getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                        callback);
                }
            });
            decor.setOnApplyWindowInsetsListener((v, in) -> {
                if (Boolean.TRUE.equals(v.getTag()) && d.isShowing()) {
                    android.graphics.Rect bounds =
                        getWindowManager().getCurrentWindowMetrics().getBounds();
                    android.graphics.Insets bars = in.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    int available = bounds.height() - bars.top
                        - Math.max(bars.bottom, in.getInsets(WindowInsets.Type.ime()).bottom)
                        - dp(20);
                    int height =
                        Math.max(dp(120), Math.min((int) (bounds.height() * .78), available));
                    WindowManager.LayoutParams lp = d.getWindow().getAttributes();
                    if (lp.height != height)
                        d.getWindow().setLayout(Math.min(bounds.width() - dp(24), dp(620)), height);
                }
                return v.onApplyWindowInsets(in);
            });
            d.setOnKeyListener((dlg, key, event) -> {
                if (key == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    back(d, () -> {
                        if (!fixedDialogs.contains(d))
                            d.cancel();
                    });
                    return true;
                }
                return false;
            });
        }
        return d;
    }
    private AlertDialog.Builder alert() {
        return new AlertDialog.Builder(this) {
            @Override
            public AlertDialog create() {
                return track(super.create());
            }
            @Override
            public AlertDialog show() {
                AlertDialog d = create();
                d.show();
                return d;
            }
        };
    }
    private void sizeDialog(Dialog d, boolean large) {
        d.getWindow().getDecorView().setTag(large);
        d.show();
        fitDialog(d);
    }
    private void fitDialog(Dialog d) {
        Window w = d.getWindow();
        if (w != null) {
            android.graphics.Rect b = getWindowManager().getCurrentWindowMetrics().getBounds();
            w.setLayout(Math.min(b.width() - dp(24), dp(620)),
                Boolean.TRUE.equals(w.getDecorView().getTag())
                    ? (int) (b.height() * .78)
                    : WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }
    private void processDialog() {
        LinearLayout box = col();
        box.setPadding(dp(18), dp(20), dp(18), dp(14));
        TextView title = text("选择应用进程", 21, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);
        gap(box, 10);
        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(0xff3a3d49));
        list.setDividerHeight(dp(1));
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog d = dialog("", box);
        sizeDialog(d, true);
        io.execute(() -> {
            try {
                List<JSONObject> data = CaptureService.processes(this);
                List<AppEntry> all = new ArrayList<>();
                PackageManager pm = getPackageManager();
                Map<String, AppEntry> apps = new HashMap<>();
                for (JSONObject j : data) {
                    String cmd = j.optString("name"), pkg = cmd.split(":", 2)[0];
                    if (pkg.equals(getPackageName()))
                        continue;
                    AppEntry e = new AppEntry();
                    e.pid = j.optInt("pid");
                    e.uid = j.optInt("uid");
                    e.rss = j.optLong("rss");
                    e.started = j.optLong("started");
                    e.cmd = cmd;
                    e.pkg = pkg;
                    e.name = pkg;
                    e.main = cmd.equals(pkg);
                    if (e.uid == prefs.getInt("uid", -1)
                        && (e.pid == prefs.getInt("pid", -1)
                            || (prefs.getInt("pid", -1) < 0 && e.main
                                && e.pkg.equals(prefs.getString("package", "")))))
                        prefs.edit().putInt("pid", e.pid).putLong("rss", e.rss).apply();
                    AppEntry cached = apps.get(pkg);
                    if (cached != null) {
                        e.name = cached.name;
                        e.icon = cached.icon;
                        e.system = cached.system;
                        e.game = cached.game;
                    } else
                        try {
                            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                            e.name = pm.getApplicationLabel(info).toString();
                            e.icon = pm.getApplicationIcon(info);
                            e.system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                            e.game = info.category == ApplicationInfo.CATEGORY_GAME;
                        } catch (Exception ignored) {
                        }
                    if (cached == null)
                        apps.put(pkg, e);
                    all.add(e);
                }
                all.sort((a, b) -> {
                    long oa = prefs.getLong("recent:" + a.pkg, 0),
                         ob = prefs.getLong("recent:" + b.pkg, 0);
                    if (oa != ob)
                        return Long.compare(ob, oa);
                    if (a.game != b.game)
                        return a.game ? -1 : 1;
                    if (a.system != b.system)
                        return a.system ? 1 : -1;
                    if (a.main != b.main)
                        return a.main ? -1 : 1;
                    if (a.started != b.started)
                        return Long.compare(b.started, a.started);
                    return Integer.compare(b.pid, a.pid);
                });
                post(() -> {
                    if (!d.isShowing())
                        return;
                    List<AppEntry> shown = new ArrayList<>(all);
                    BaseAdapter adapter = new BaseAdapter() {
                        public int getCount() {
                            return shown.size();
                        }
                        public Object getItem(int i) {
                            return shown.get(i);
                        }
                        public long getItemId(int i) {
                            return shown.get(i).pid;
                        }
                        public View getView(int i, View convert, android.view.ViewGroup parent) {
                            AppEntry e = shown.get(i);
                            LinearLayout line = row();
                            line.setPadding(dp(4), dp(12), dp(4), dp(12));
                            ImageView icon = new ImageView(WorkspaceUi.this);
                            icon.setImageDrawable(e.icon == null
                                    ? getDrawable(android.R.drawable.sym_def_app_icon)
                                    : e.icon);
                            line.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
                            LinearLayout labels = col();
                            labels.setPadding(dp(12), 0, 0, 0);
                            labels.addView(text(e.name, 15, TEXT));
                            labels.addView(text(e.cmd, 11, MUTED));
                            labels.addView(text("PID " + e.pid + " · " + DisplayFormat.memory(e.rss)
                                    + (e.main ? " · 主进程" : " · 子进程"),
                                11, ACCENT));
                            line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                            return line;
                        }
                    };
                    list.setAdapter(adapter);
                    list.setOnItemClickListener((a, v, p, id) -> {
                        if (CaptureService.active) {
                            toast("当前会话目标已固定，请停止后切换");
                            return;
                        }
                        AppEntry e = shown.get(p);
                        prefs.edit()
                            .putInt("uid", e.uid)
                            .putInt("pid", e.pid)
                            .putLong("rss", e.rss)
                            .putString("label", e.name)
                            .putString("package", e.pkg)
                            .putBoolean("scopeChosen", true)
                            .putLong("recent:" + e.pkg, System.currentTimeMillis())
                            .apply();
                        d.dismiss();
                        showTab(0);
                    });
                });
            } catch (Exception e) {
                post(() -> {
                    if (d.isShowing()) {
                        d.dismiss();
                        error("读取进程失败", e);
                    }
                });
            }
        });
    }
    private static final class AppEntry {
        int pid, uid;
        long rss, started;
        String name, pkg, cmd;
        boolean system, game, main;
        Drawable icon;
    }
    private interface Changed {
        void changed(String s);
    }
    private android.text.TextWatcher watcher(Changed c) {
        return new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int count, int after) {
            }
            public void onTextChanged(CharSequence s, int st, int before, int count) {
                c.changed(s.toString());
            }
            public void afterTextChanged(android.text.Editable e) {
            }
        };
    }
    private void packetsPage() {
        LinearLayout page = col();
        body.addView(page);
        LinearLayout tools = row();
        tools.addView(text("通信记录", 20, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(button("更多", false, () -> dataMenu(tools)));
        tools.addView(button("刷新", false, () -> {
            clearedAfterSequence = 0;
            loadPackets(true);
        }));
        page.addView(tools);
        gap(page, 10);
        search = "";
        LinearLayout opts = row();
        CheckBox auto = new CheckBox(this);
        auto.setText("实时刷新");
        auto.setTextColor(MUTED);
        auto.setTextSize(12);
        auto.setChecked(liveRefresh);
        auto.setOnCheckedChangeListener((b, v) -> liveRefresh = v);
        opts.addView(auto, new LinearLayout.LayoutParams(0, -2, 1));
        Button proto = button(searchProto == 6 ? "TCP"
                : searchProto == 17            ? "UDP"
                                               : "所有协议",
            false, () -> {});
        proto.setOnClickListener(v
            -> alert()
                .setTitle("显示的协议")
                .setItems(new String[] {"所有协议", "TCP", "UDP"},
                    (d, n) -> {
                        searchProto = n == 1 ? 6 : n == 2 ? 17 : 0;
                        proto.setText(n == 1 ? "TCP" : n == 2 ? "UDP" : "所有协议");
                        loadPackets(true);
                    })
                .setNegativeButton("取消", null)
                .show());
        opts.addView(proto);

        page.addView(opts);
        gap(page, 8);
        packetList = new ListView(this);
        packetList.setDivider(new ColorDrawable(BG));
        packetList.setDividerHeight(dp(5));
        page.addView(packetList, new LinearLayout.LayoutParams(-1, 0, 1));
        packetAdapter = new BaseAdapter() {
            public int getCount() {
                return records.size();
            }
            public Object getItem(int i) {
                return records.get(i);
            }
            public long getItemId(int i) {
                return records.get(i).id;
            }
            public View getView(int i, View convert, android.view.ViewGroup parent) {
                PacketStore.Record r = records.get(i);
                LinearLayout item = col();
                item.setPadding(dp(14), dp(12), dp(14), dp(12));
                item.setBackground(background(SURFACE, 12));
                LinearLayout head = row();
                TextView p = text(r.protocol(), 13, r.proto == 17 ? ACCENT : 0xffa7c7ff);
                p.setTypeface(null, Typeface.BOLD);
                head.addView(p, new LinearLayout.LayoutParams(0, -2, 1));
                head.addView(text(r.time() + "  #" + r.id, 11, MUTED));
                item.addView(head);
                gap(item, 5);
                TextView route = text(r.route(), 12, TEXT);
                route.setMaxLines(2);
                item.addView(route);
                gap(item, 5);
                item.addView(text((r.dir == 1          ? "↑ 发出"
                                          : r.dir == 0 ? "↓ 收到"
                                                       : "↔ 方向未知")
                        + " · " + r.caplen + " B · IPv" + r.version
                        + ((r.flags & 1) != 0 ? " · 分片" : ""),
                    11, MUTED));
                return item;
            }
        };
        packetList.setAdapter(packetAdapter);
        packetList.setOnItemClickListener((a, v, p, id) -> detail(records.get(p)));
        gap(page, 6);
        page.addView(button("加载更早的匹配记录", false, () -> {
            liveRefresh = false;
            auto.setChecked(false);
            loadPackets(false);
        }));
        if (selectedSession == null && CaptureService.activeSession != null)
            selectedSession = CaptureService.activeSession;
        if (selectedSession != null)
            loadPackets(true);
    }
    private void loadPackets(boolean reset) {
        if (tab != 1 || selectedSession == null)
            return;
        if (!reset && queryBusy)
            return;
        if (!reset && (cursor < 0 || cursor >= PacketStore.count(selectedSession))) {
            toast("已经到达当前结果末尾");
            return;
        }
        int g = ++generation;
        queryBusy = true;
        lastRefresh = System.currentTimeMillis();
        File session = selectedSession;
        long afterSequence = session.equals(clearedSession) ? clearedAfterSequence : 0;
        long start = reset ? -1 : cursor;
        String term = search;
        int proto = searchProto;

        io.execute(() -> {
            try {
                PacketStore.Query q = PacketStore.query(session, start, true, term, proto,
                    () -> generation != g || Thread.currentThread().isInterrupted(), afterSequence);
                post(() -> {
                    if (generation != g || tab != 1)
                        return;
                    queryBusy = false;
                    if (reset)
                        records.clear();
                    records.addAll(q.records);
                    if (records.size() > 1600)
                        records.subList(0, records.size() - 1600).clear();
                    cursor = q.next;
                    packetAdapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                post(() -> {
                    if (generation == g) {
                        queryBusy = false;
                        error("读取记录失败", e);
                    }
                });
            }
        });
    }
    private void detail(PacketStore.Record r) {
        LinearLayout box = col();
        box.setPadding(dp(16), dp(18), dp(16), dp(12));
        box.addView(text("记录 #" + r.id + " · " + r.protocol(), 20, TEXT));
        gap(box, 6);
        box.addView(text(r.route() + "\n" + r.time() + " · 接口 " + r.iface + " · 捕获 " + r.caplen
                + " / 原始 " + r.wirelen + " B",
            11, MUTED));
        gap(box, 12);
        LinearLayout actions = row();
        ScrollView sc = scroll();
        TextView content = text("正在读取原始记录…", 12, TEXT);
        content.setTextIsSelectable(true);
        content.setTypeface(Typeface.MONOSPACE);
        content.setPadding(dp(4), dp(8), dp(4), dp(8));
        sc.addView(content);
        HexDumpView hex = new HexDumpView(this);
        box.addView(actions);
        gap(box, 8);
        box.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog d = dialog("", box);
        fullButton(box, "关闭", false, () -> d.dismiss());
        sizeDialog(d, true);
        io.execute(() -> {
            try {
                byte[] raw = PacketStore.bytes(r), payload = PacketStore.payload(r, raw);
                String readable = PacketStore.readable(payload);
                post(() -> {
                    if (!d.isShowing())
                        return;
                    content.setText(readable);
                    hex.setBytes(raw);
                    for (String name : new String[] {"可读内容", "原始记录", "标记", "比较"}) {
                        Button b = button(name, false, () -> {
                            if (name.equals("可读内容")) {
                                sc.removeAllViews();
                                sc.addView(content);
                                content.setText(readable);
                                sc.scrollTo(0, 0);
                            } else if (name.equals("原始记录")) {
                                sc.removeAllViews();
                                sc.addView(hex);
                                sc.scrollTo(0, 0);
                            } else if (name.equals("标记")) {
                                pinned = r;
                                pinnedPayload = payload.clone();
                                toast("已标记 #" + r.id + "，选择另一条记录后可比较载荷");
                            } else {
                                if (pinnedPayload == null) {
                                    toast("请先标记一条记录");
                                    return;
                                }
                                sc.removeAllViews();
                                sc.addView(content);
                                content.setText("A：#" + pinned.id + "   B：#" + r.id + "\n"
                                    + PacketStore.diff(pinnedPayload, payload));
                                sc.scrollTo(0, 0);
                            }
                        });
                        b.setTextSize(11);
                        b.setPadding(dp(2), 0, dp(2), 0);
                        actions.addView(b, new LinearLayout.LayoutParams(0, dp(42), 1));
                    }
                });
            } catch (Exception e) {
                post(() -> content.setText("读取失败：" + e.getMessage()));
            }
        });
    }
    // 为什么：清空只影响当前视图，原始记录和历史导出不能随预览一起丢失。
    private void dataMenu(View anchor) {
        alert()
            .setTitle("数据操作")
            .setItems(new String[] {"连接概览", "清空当前视图", "导出会话"},
                (d, option) -> {
                    if (option == 0)
                        flowDialog();
                    else if (selectedSession == null)
                        toast("请先选择会话");
                    else if (option == 2)
                        exportDialog(selectedSession);
                    else {
                        clearedSession = selectedSession;
                        clearedAfterSequence = PacketStore.count(selectedSession);
                        loadPackets(true);
                        toast("视图已清空，原始记录保留；点击刷新可恢复查看全部");
                    }
                })
            .setNegativeButton("取消", null)
            .show();
    }
    private void flowDialog() {
        if (selectedSession == null) {
            toast("请先选择一个会话");
            return;
        }
        LinearLayout box = col();
        box.setPadding(dp(18), dp(18), dp(18), dp(14));
        box.addView(text("连接概览", 21, TEXT));
        gap(box, 6);
        TextView hint = text("按通信两端归类，计算中…", 12, MUTED);
        box.addView(hint);
        ListView list = new ListView(this);
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog d = dialog("", box);
        fullButton(box, "关闭", false, () -> d.dismiss());
        sizeDialog(d, true);
        File f = selectedSession;
        io.execute(() -> {
            try {
                List<String> items = PacketStore.flows(
                    f, () -> !d.isShowing() || Thread.currentThread().isInterrupted());
                post(() -> {
                    if (!d.isShowing())
                        return;
                    hint.setText(items.size() + " 条连接 · 仅为本次已捕获数据的汇总");
                    list.setAdapter(simpleAdapter(items));
                });
            } catch (Exception e) {
                post(() -> hint.setText("读取失败：" + e.getMessage()));
            }
        });
    }
    private BaseAdapter simpleAdapter(List<String> items) {
        return new BaseAdapter() {
            public int getCount() {
                return items.size();
            }
            public Object getItem(int p) {
                return items.get(p);
            }
            public long getItemId(int p) {
                return p;
            }
            public View getView(int p, View cv, android.view.ViewGroup parent) {
                TextView t = text(items.get(p), 13, TEXT);
                t.setPadding(dp(8), dp(14), dp(8), dp(14));
                return t;
            }
        };
    }
    private BaseAdapter historyAdapter(List<String> items) {
        return new BaseAdapter() {
            public int getCount() {
                return items.size();
            }
            public Object getItem(int p) {
                return items.get(p);
            }
            public long getItemId(int p) {
                return p;
            }
            public View getView(int p, View cv, ViewGroup parent) {
                TextView item = text(items.get(p), 14, TEXT);
                item.setPadding(dp(14), dp(14), dp(14), dp(14));
                item.setBackground(background(SURFACE, 14));
                return item;
            }
        };
    }
    private void history() {
        LinearLayout page = col();
        body.addView(page);
        LinearLayout head = row();
        head.addView(text("抓包历史", 21, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(button("导入 PCAP", false, () -> {
            pathDialog(Environment.getExternalStorageDirectory(), true, f -> importFile(f));
        }));
        page.addView(head);
        gap(page, 10);
        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        list.setDividerHeight(dp(10));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        File base = baseDir();
        io.execute(() -> {
            List<File> sessions = PacketStore.history(this, base);
            List<String> labels = new ArrayList<>();
            for (File f : sessions) {
                JSONObject m = PacketStore.manifest(f);
                String state = m.optString("state");
                String status = state.equals("saved") ? "已保存"
                    : state.equals("imported")        ? "已导入"
                    : state.equals("attention")       ? "存在丢弃或写入提示"
                    : CaptureService.active && f.equals(CaptureService.activeSession)
                    ? "抓包中"
                    : "需检查中断状态";
                labels.add(m.optString("label", f.getName()) + "\n" + f.getName() + "\n"
                    + PacketStore.count(f) + " 包 · " + status
                    + (m.optString("scope").equals("demo") ? " · 合成演示" : ""));
            }
            post(() -> {
                if (tab != 2)
                    return;
                list.setAdapter(historyAdapter(labels));
                list.setOnItemClickListener((a, v, p, id) -> {
                    selectedSession = sessions.get(p);
                    showTab(1);
                });
                list.setOnItemLongClickListener((a, v, p, id) -> {
                    exportDialog(sessions.get(p));
                    return true;
                });
            });
        });
    }
    private void exportDialog(File session) {
        if (CaptureService.active && session.equals(CaptureService.activeSession)) {
            toast("请先停止并保存，再导出完整会话");
            return;
        }
        String[] labels = {"完整会话 ZIP（原包、索引、状态）", "标准 PCAP（合并所有分段）",
            "JSONL（网络字段和载荷字节）", "十六进制文本（完整记录）"};
        String[] types = {"zip", "pcap", "jsonl", "txt"};
        alert()
            .setTitle("导出会话")
            .setItems(labels,
                (d, p) -> {
                    String type = types[p];
                    File target = new File(getExternalFilesDir(null), "exports");
                    if (!target.isDirectory() && !target.mkdirs()) {
                        toast("无法创建默认导出目录");
                        return;
                    }
                    folderDialog(target, dir -> exportName(session, dir, type));
                })
            .setNegativeButton("取消", null)
            .show();
    }
    private ProgressDialog progress(String title, String message, boolean cancelable) {
        ProgressDialog d = track(new ProgressDialog(this));
        d.setTitle(title);
        d.setMessage(message);
        d.setIndeterminate(true);
        d.setCancelable(cancelable);
        if (!cancelable)
            fixedDialogs.add(d);
        d.show();
        return d;
    }
    private void exportName(File session, File dir, String type) {
        EditText name = input("导出文件名");
        name.setText(session.getName() + "." + type);
        LinearLayout box = col();
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.addView(name);
        AlertDialog d = alert()
                            .setTitle("导出到 " + dir.getName())
                            .setView(box)
                            .setPositiveButton("保存", null)
                            .setNegativeButton("取消", null)
                            .create();
        d.setOnShowListener(v -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            String filename = name.getText().toString().trim();
            if (filename.isEmpty() || filename.equals(".") || filename.equals("..")
                || filename.contains("/") || filename.contains("\\")
                || filename.indexOf('\0') >= 0) {
                name.setError("请输入有效的文件名");
                return;
            }
            File target = new File(dir, filename);
            if (target.exists()) {
                name.setError("同名文件已存在，请更换名称");
                return;
            }
            d.dismiss();
            ProgressDialog wait = progress("导出中", "正在读取全部原始记录…", false);
            io.execute(() -> {
                File partial = new File(dir, "." + filename + "." + UUID.randomUUID() + ".partial");
                try {
                    try (FileOutputStream out = new FileOutputStream(partial)) {
                        PacketStore.export(session, out, type);
                        out.getFD().sync();
                    }
                    if (target.exists() || !partial.renameTo(target))
                        throw new IOException("文件已生成，但最终命名失败。临时文件：" + partial);
                    post(() -> {
                        wait.dismiss();
                        if (isVisible())
                            alert()
                                .setTitle("导出完成")
                                .setMessage(target.getAbsolutePath())
                                .setPositiveButton("关闭", null)
                                .show();
                        else
                            toast("导出完成：" + target.getAbsolutePath());
                    });
                } catch (Exception e) {
                    post(() -> {
                        wait.dismiss();
                        error("导出失败，临时文件已保留", e);
                    });
                }
            });
        }));
        d.show();
    }
    private void importFile(File file) {
        ProgressDialog wait = progress("导入中", "正在检查 PCAP 并建立索引…", false);
        io.execute(() -> {
            File dir = null;
            try (InputStream in = new FileInputStream(file)) {
                File base = new File(getExternalFilesDir(null), "captures");
                base.mkdirs();
                dir = new File(base,
                    "导入_" + System.currentTimeMillis() + "_"
                        + UUID.randomUUID().toString().substring(0, 6));
                if (!dir.mkdir())
                    throw new IOException("无法创建目录");
                JSONObject m = new JSONObject()
                                   .put("label", file.getName())
                                   .put("state", "importing")
                                   .put("started", System.currentTimeMillis())
                                   .put("scope", "import");
                PacketStore.saveManifest(dir, m);
                PacketStore.importPcap(in, dir, m);
                File done = dir;
                post(() -> {
                    wait.dismiss();
                    selectedSession = done;
                    if (isVisible())
                        showTab(1);
                });
            } catch (Exception e) {
                if (dir != null)
                    try {
                        JSONObject m = PacketStore.manifest(dir);
                        PacketStore.saveManifest(
                            dir, m.put("state", "error").put("error", e.toString()));
                    } catch (Exception ignored) {
                    }
                post(() -> {
                    wait.dismiss();
                    error("导入失败，部分记录已保留", e);
                });
            }
        });
    }
    private void settings() {
        ScrollView sc = scroll();
        LinearLayout page = col();
        sc.addView(page);
        body.addView(sc);
        LinearLayout storage = card(page, "保存位置");
        TextView path = text(baseDir().getAbsolutePath(), 14, ACCENT);
        path.setTextIsSelectable(true);
        gap(storage, 10);
        storage.addView(path);
        fullButton(storage, "… 选择目录", false, () -> folderDialog(baseDir(), f -> {
            Set<String> roots = new HashSet<>(prefs.getStringSet("roots", Collections.emptySet()));
            roots.add(f.getAbsolutePath());
            prefs.edit()
                .putString("directory", f.getAbsolutePath())
                .putStringSet("roots", roots)
                .apply();
            path.setText(f.getAbsolutePath());
        }));
        EditText prefix = input("文件名前缀");
        prefix.setText(prefs.getString("prefix", "抓包"));
        gap(storage, 12);
        storage.addView(text("文件名前缀", 14, TEXT));
        gap(storage, 6);
        storage.addView(prefix);
        prefix.addTextChangedListener(watcher(s -> prefs.edit().putString("prefix", s).apply()));
        LinearLayout advanced = card(page, "高级抓取设置");
        gap(advanced, 10);
        advanced.addView(text("保存的协议范围", 14, TEXT));
        int current = prefs.getInt("protocol", 0);
        Button protocol = button(current == 6 ? "仅 TCP"
                : current == 17               ? "仅 UDP"
                                              : "全部 IP 协议",
            false, () -> {});
        advanced.addView(protocol, new LinearLayout.LayoutParams(-1, dp(46)));
        protocol.setOnClickListener(v
            -> alert()
                .setTitle("保存的协议范围")
                .setItems(new String[] {"全部 IP 协议", "仅 TCP", "仅 UDP"},
                    (d, n) -> {
                        prefs.edit().putInt("protocol", n == 1 ? 6 : n == 2 ? 17 : 0).apply();
                        protocol.setText(n == 1 ? "仅 TCP" : n == 2 ? "仅 UDP" : "全部 IP 协议");
                    })
                .setNegativeButton("取消", null)
                .show());
        fullButton(advanced, "选择接口：" + prefs.getString("ifaceName", "所有接口"), false,
            () -> interfaces());
        LinearLayout appearance = card(page, "界面与交互");
        TextView alphaLabel = text("背景不透明度 " + prefs.getInt("opacity", 86) + "%", 14, TEXT);
        appearance.addView(alphaLabel);
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(50);
        opacity.setProgress(prefs.getInt("opacity", 86) - 45);
        appearance.addView(opacity, new LinearLayout.LayoutParams(-1, dp(44)));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) {
            }
            public void onStopTrackingTouch(SeekBar b) {
            }
            public void onProgressChanged(SeekBar b, int value, boolean user) {
                if (!user)
                    return;
                int alpha = value + 45;
                prefs.edit().putInt("opacity", alpha).apply();
                alphaLabel.setText("背景不透明度 " + alpha + "%");
                applyBackdrop();
            }
        });
        gap(appearance, 10);
        Switch wake = new Switch(this);
        wake.setText("熄屏保持抓包（增加耗电）");
        wake.setTextColor(TEXT);
        wake.setChecked(prefs.getBoolean("wake", true));
        wake.setOnCheckedChangeListener((b, v) -> prefs.edit().putBoolean("wake", v).apply());
        appearance.addView(wake);
        LinearLayout about = card(page, "工具");
        fullButton(about, "检查 Root 核心", false, () -> probe());
        fullButton(about, "导出设备与会话诊断", false, () -> diagnostic());
        fullButton(about, "结束工具", false, () -> {
            Runnable exit = () -> {
                service.exitTool();
            };
            if (CaptureService.active)
                alert()
                    .setTitle("停止抓包并退出？")
                    .setMessage("现有队列将先写入文件，随后结束工具。")
                    .setPositiveButton("保存并退出", (d, w) -> exit.run())
                    .setNegativeButton("取消", null)
                    .show();
            else
                exit.run();
        });
        gap(page, 12);
    }
    private void interfaces() {
        List<String> names = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        names.add("所有接口");
        ids.add(0);
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface n = all.nextElement();
                if (n.isUp()) {
                    names.add(n.getName() + " (" + n.getIndex() + ")");
                    ids.add(n.getIndex());
                }
            }
        } catch (Exception e) {
            error("读取接口失败", e);
            return;
        }
        alert()
            .setTitle("选择观测接口")
            .setItems(names.toArray(new String[0]),
                (d, p) -> {
                    prefs.edit()
                        .putInt("iface", ids.get(p))
                        .putString("ifaceName", names.get(p))
                        .apply();
                    showTab(3);
                })
            .setNegativeButton("取消", null)
            .show();
    }
    private interface FolderChosen {
        void choose(File f);
    }
    private void folderDialog(File start, FolderChosen chosen) {
        if (!start.isDirectory() && !start.mkdirs()) {
            toast("无法打开默认目录，请检查存储权限");
            start = getExternalFilesDir(null);
        }
        pathDialog(start, false, chosen);
    }
    private void pathDialog(File start, boolean files, FolderChosen chosen) {
        LinearLayout box = col();
        box.setPadding(dp(18), dp(18), dp(18), dp(14));
        box.addView(text(files ? "选择 PCAP 文件" : "选择保存目录", 21, TEXT));
        gap(box, 10);
        TextView path = text("", 12, ACCENT);
        path.setMaxLines(3);
        box.addView(path);
        gap(box, 10);
        LinearLayout tools = row();
        box.addView(tools);
        ListView list = new ListView(this);
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog d = dialog("", box);
        File[] current = {start};
        List<File> dirs = new ArrayList<>();
        boolean[] sortByTime = {false};
        final Runnable[] scan = {null};
        scan[0] = () -> {
            File here = current[0];
            path.setText(here.getAbsolutePath());
            io.execute(() -> {
                File[] children = here.listFiles();
                List<File> found = new ArrayList<>();
                if (children != null)
                    for (File f : children)
                        if (f.isDirectory() || (files && f.isFile()))
                            found.add(f);
                found.sort((a, b) -> {
                    if (a.isDirectory() != b.isDirectory())
                        return a.isDirectory() ? -1 : 1;
                    return sortByTime[0] && a.lastModified() != b.lastModified()
                        ? Long.compare(b.lastModified(), a.lastModified())
                        : a.getName().compareToIgnoreCase(b.getName());
                });
                post(() -> {
                    if (!d.isShowing() || !current[0].equals(here))
                        return;
                    dirs.clear();
                    dirs.addAll(found);
                    List<String> labels = new ArrayList<>();
                    for (File f : dirs)
                        labels.add((f.isDirectory() ? "▸  " : "▤  ") + f.getName());
                    list.setAdapter(simpleAdapter(labels));
                    if (children == null)
                        toast("当前目录不可读取，请授权内部存储访问或选择应用目录");
                });
            });
        };
        tools.addView(button("新建文件夹", false, () -> nameDialog(current[0], scan[0])),
            new LinearLayout.LayoutParams(0, dp(46), 1));
        Button sort = button("按时间", false, () -> {
            sortByTime[0] = !sortByTime[0];
            scan[0].run();
        });
        tools.addView(sort, new LinearLayout.LayoutParams(0, dp(46), 1));
        sort.setOnClickListener(v -> {
            sortByTime[0] = !sortByTime[0];
            sort.setText(sortByTime[0] ? "按名称" : "按时间");
            scan[0].run();
        });
        tools.addView(button("上一级 ↑", false, () -> {
            File parent = current[0].getParentFile();
            if (parent != null) {
                current[0] = parent;
                scan[0].run();
            }
        }), new LinearLayout.LayoutParams(0, dp(46), 1));
        list.setOnItemClickListener((a, v, p, id) -> {
            File picked = dirs.get(p);
            if (picked.isDirectory()) {
                current[0] = picked;
                scan[0].run();
            } else if (files) {
                d.dismiss();
                chosen.choose(picked);
            }
        });
        list.setOnItemLongClickListener((a, v, p, id) -> {
            if (CaptureService.active) {
                toast("抓包期间请勿重命名目录，避免影响会话路径");
                return true;
            }
            nameDialog(current[0], dirs.get(p), scan[0]);
            return true;
        });
        fullButton(box, "授权内部存储访问", false, () -> {
            if (Environment.isExternalStorageManager()) {
                toast("已获得内部存储文件访问权限");
                current[0] = Environment.getExternalStorageDirectory();
                scan[0].run();
            } else
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        });
        fullButton(box, "转到内部存储", false, () -> {
            current[0] = Environment.getExternalStorageDirectory();
            scan[0].run();
        });
        if (!files)
            fullButton(box, "选定当前目录", true, () -> {
                File f = current[0];
                if (!f.exists() && !f.mkdirs()) {
                    toast("无法创建当前目录");
                    return;
                }
                if (!f.isDirectory() || !f.canWrite()) {
                    toast("此目录不可写，请选择其他目录或授予文件访问权限");
                    return;
                }
                chosen.choose(f);
                d.dismiss();
            });
        fullButton(box, "取消", false, () -> d.dismiss());
        sizeDialog(d, true);
        scan[0].run();
    }
    private void nameDialog(File parent, Runnable refresh) {
        nameDialog(parent, null, refresh);
    }
    private void nameDialog(File parent, File original, Runnable refresh) {
        EditText name = input("文件夹名称");
        if (original != null) {
            name.setText(original.getName());
            name.selectAll();
        }
        LinearLayout box = col();
        box.setPadding(dp(22), dp(12), dp(22), dp(4));
        box.addView(name);
        AlertDialog d = alert()
                            .setTitle(original == null ? "新建文件夹" : "重命名文件夹")
                            .setView(box)
                            .setPositiveButton(original == null ? "创建" : "重命名", null)
                            .setNegativeButton("取消", null)
                            .create();
        d.setCanceledOnTouchOutside(true);
        d.setOnShowListener(v -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
                String s = name.getText().toString().trim();
                if (s.isEmpty() || s.equals(".") || s.equals("..") || s.contains("/")
                    || s.contains("\\") || s.indexOf('\0') >= 0) {
                    name.setError("请输入有效的单层文件夹名");
                    return;
                }
                File f = new File(parent, s);
                if (f.exists()) {
                    name.setError("同名目录或文件已经存在");
                    return;
                }
                if (!(original == null ? f.mkdir() : original.renameTo(f))) {
                    name.setError("操作失败，请检查目录写入权限");
                    return;
                }
                d.dismiss();
                refresh.run();
            });
            name.requestFocus();
            ViewTreeObserver.OnWindowFocusChangeListener focus =
                new ViewTreeObserver.OnWindowFocusChangeListener() {
                    public void onWindowFocusChanged(boolean focused) {
                        if (!focused || !d.isShowing())
                            return;
                        name.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                        name.post(()
                                      -> getSystemService(InputMethodManager.class)
                                          .showSoftInput(name, InputMethodManager.SHOW_IMPLICIT));
                    }
                };
            name.getViewTreeObserver().addOnWindowFocusChangeListener(focus);
            if (name.hasWindowFocus())
                focus.onWindowFocusChanged(true);
        });
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(background(0xff282a35, 20));
            d.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }
    private void demo() {
        io.execute(() -> {
            try {
                File f = PacketStore.demo(this);
                post(() -> {
                    selectedSession = f;
                    showTab(1);
                    toast("已打开合成演示数据，并非真实抓包");
                });
            } catch (Exception e) {
                post(() -> error("演示创建失败", e));
            }
        });
    }
    private void probe() {
        ProgressDialog d = progress("检查 Root", "请留意 Root 管理器的授权提示…", true);
        io.execute(() -> {
            try {
                StringBuilder s = new StringBuilder();
                for (String line : CaptureService.command(this, "probe")) {
                    try {
                        s.append(new JSONObject(line).optString("message", line)).append('\n');
                    } catch (Exception ignored) {
                        s.append(line).append('\n');
                    }
                }
                post(()
                         -> alert()
                             .setTitle("Root 与抓包 socket 检查通过")
                             .setMessage(s.toString())
                             .setPositiveButton("关闭", null)
                             .show());
            } catch (Exception e) {
                post(() -> error("Root 检查失败", e));
            } finally {
                post(() -> d.dismiss());
            }
        });
    }
    private void diagnostic() {
        try {
            JSONObject j = new JSONObject()
                               .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                               .put("android", Build.VERSION.RELEASE)
                               .put("api", Build.VERSION.SDK_INT)
                               .put("build", Build.DISPLAY)
                               .put("kernel", System.getProperty("os.version"))
                               .put("abis", Arrays.toString(Build.SUPPORTED_ABIS))
                               .put("overlay", Settings.canDrawOverlays(this))
                               .put("allFiles", Environment.isExternalStorageManager())
                               .put("session", CaptureService.snapshot());
            File cache =
                new File(getExternalFilesDir(null), "captures/诊断_" + System.currentTimeMillis());
            cache.mkdirs();
            PacketStore.saveManifest(cache, j.put("label", "设备诊断").put("state", "imported"));
            exportDialog(cache);
        } catch (Exception e) {
            error("生成诊断失败", e);
        }
    }
    private void error(String title, Exception e) {
        if (!isVisible()) {
            toast(title + "：" + e);
            return;
        }
        alert()
            .setTitle(title)
            .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
            .setPositiveButton("关闭", null)
            .show();
    }
    @Override
    public void startActivity(Intent intent) {
        // 权限由系统页面处理。隐藏悬浮层，避免挡住授权页；回到悬浮球重新展开。
        service.collapseWorkspace();
        super.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private void navigateBack() {
        if (tab != 0) {
            showTab(0);
            return;
        }
        collapse();
    }
}
