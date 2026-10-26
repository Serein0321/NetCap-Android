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
import java.io.*;
import java.lang.Process;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

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
    private TextView status, stats, scope, durationText, rateText, directionText, qualityText;
    private long rateAt, rateBytes, ratePackets;
    private String rateSession = "";
    private boolean wideLayout;
    private final ExecutorService processIo = Executors.newSingleThreadExecutor();
    private ProcessCatalog catalog;
    private boolean processBusy;
    private List<AppEntry> processCache = new ArrayList<>();
    private final Map<String, AppEntry> appCache = new LinkedHashMap<>();
    private final List<Runnable> processListeners = new ArrayList<>();
    private Exception processError;
    private long appCacheAt;
    private PacketFilter packetFilter = new PacketFilter();
    private int dataView;
    private String relatedKey = "", relatedLabel = "";
    private Map<Long, String> bookmarks = new LinkedHashMap<>();
    private final Map<Long, PacketStore.Record> selected = new LinkedHashMap<>();
    private File dataSession;
    private TextView dataInfo;
    private final List<TextView> viewTabs = new ArrayList<>(), viewBadges = new ArrayList<>();
    private long relatedCount = -1;
    private ToolIcon saveTool, compareTool, filterTool, searchTool;
    private long knownCount = -1;
    private boolean scrollBusy, exhausted, pageFailed, hasNewer;
    private final Set<Long> loadedIds = new HashSet<>();
    private Button captureButton;
    private File selectedSession;
    private long cursor = -1;
    private boolean queryBusy = false, liveRefresh = true;
    private volatile int generation = 0;
    private ListView packetList;
    private final List<PacketStore.Record> records = new ArrayList<>();
    private final List<Dialog> openDialogs = new ArrayList<>();
    private final Set<Dialog> fixedDialogs = new HashSet<>();
    private BaseAdapter packetAdapter;
    private long lastRefresh = 0;
    private final Runnable tick = new Runnable() {
        public void run() {
            updateStatus();
            if (tab == 1 && selectedSession != null
                && selectedSession.equals(CaptureService.activeSession) && liveRefresh
                && dataView == 0 && selected.isEmpty() && !scrollBusy && !queryBusy
                && packetList != null && packetList.getFirstVisiblePosition() == 0
                && System.currentTimeMillis() - lastRefresh > 1800
                && PacketStore.count(selectedSession) != knownCount)
                loadPackets(true);
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
        catalog = new ProcessCatalog(this);
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
        refreshProcesses(null);
    }
    void hide() {
        resumeAfterRoot = false;
        rootHidden.clear();
        visible = false;
        generation++;
        queryBusy = false;
        ui.removeCallbacks(tick);
        getWindow().getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(workspaceBack);
        for (Dialog d : new ArrayList<>(openDialogs)) d.dismiss();
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
        catalog.close();
        processIo.shutdownNow();
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
        for (Runnable r : pending) r.run();
    }
    void resized() {
        if (isVisible()) {
            getWindow().setLayout(-1, -1);
            if (wideLayout != landscape())
                build();
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
        for (Dialog d : windows) d.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void applyBackdrop() {
        shell.setBackgroundColor((prefs.getInt("opacity", 86) * 255 / 100 << 24) | 0x1b1d25);
    }
    private boolean landscape() {
        android.graphics.Rect r = getWindowManager().getCurrentWindowMetrics().getBounds();
        return r.width() > r.height();
    }
    private void build() {
        wideLayout = landscape();
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
        header.addView(processIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        nav = row();
        header.addView(nav, new LinearLayout.LayoutParams(0, dp(40), 1));
        Button collapse = button("×", false, () -> collapse());
        collapse.setTextSize(24);
        collapse.setPadding(0, 0, 0, 0);
        collapse.setContentDescription("收起为悬浮球，抓包继续");
        header.addView(collapse, new LinearLayout.LayoutParams(dp(40), dp(40)));
        shell.addView(header);
        gap(shell, 4);
        processLabel = text("", 12, TEXT);
        processLabel.setSingleLine(true);
        processLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        processLabel.setPadding(dp(6), dp(2), dp(6), dp(2));
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
        gap(shell, 4);
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
        status = stats = scope = durationText = rateText = directionText = qualityText = null;
        captureButton = null;
        body.removeAllViews();
        nav.removeAllViews();
        String[] labels = {"抓包", "数据", "历史", "设置"};
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
        LinearLayout content = landscape() ? row() : col();
        content.setGravity(Gravity.TOP);
        sc.addView(content);
        body.addView(sc);
        LinearLayout primary = col(), secondary = col();
        if (landscape()) {
            content.addView(primary, new LinearLayout.LayoutParams(0, -2, 1));
            content.addView(secondary, new LinearLayout.LayoutParams(0, -2, 1));
        } else {
            content.addView(primary);
            content.addView(secondary);
        }
        LinearLayout hero = card(primary, "");
        LinearLayout head = row();
        status = text("准备就绪", 14, ACCENT);
        head.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        durationText = text("00:00:00", 22, TEXT);
        durationText.setTypeface(Typeface.MONOSPACE);
        head.addView(durationText);
        hero.addView(head);
        gap(hero, 8);
        stats = text("0 包 · 0 B", 23, TEXT);
        stats.setTypeface(null, Typeface.BOLD);
        hero.addView(stats);
        gap(hero, 6);
        rateText = text("0 包/s · 0 B/s", 12, MUTED);
        hero.addView(rateText);
        gap(hero, 10);
        captureButton = button("开始抓包", true, () -> {
            if (CaptureService.active)
                stopCapture();
            else
                startCapture();
        });
        hero.addView(captureButton, new LinearLayout.LayoutParams(-1, dp(46)));
        LinearLayout actions = row();
        actions.addView(compactButton("查看数据", () -> {
            selectedSession = CaptureService.activeSession;
            showTab(1);
        }), new LinearLayout.LayoutParams(0, dp(40), 1));
        actions.addView(
            compactButton("完整性报告", () -> reportDialog(CaptureService.activeSession)),
            new LinearLayout.LayoutParams(0, dp(40), 1));
        hero.addView(actions);
        LinearLayout direction = card(secondary, "通信与归属");
        directionText = text("", 13, TEXT);
        gap(direction, 6);
        direction.addView(directionText);
        LinearLayout quality = card(secondary, "保存质量");
        qualityText = text("", 12, MUTED);
        gap(quality, 6);
        quality.addView(qualityText);
        scope = text("", 12, RED);
        quality.addView(scope);
        updateStatus();
    }
    private Button compactButton(String name, Runnable action) {
        Button b = button(name, false, action);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setTextSize(12);
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
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
            ? (state.equals("checking")          ? "正在检查完整性…"
                      : CaptureService.stopping  ? "正在排空并保存…"
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
        long duration =
            !j.has("captureDurationMillis") && CaptureService.active && j.has("captureElapsedStart")
            ? Math.max(0, SystemClock.elapsedRealtime() - j.optLong("captureElapsedStart"))
            : j.optLong("captureDurationMillis");
        durationText.setText(String.format(Locale.ROOT, "%02d:%02d:%02d", duration / 3600000,
            duration / 60000 % 60, duration / 1000 % 60));
        long now = SystemClock.elapsedRealtime(),
             bytes = j.optLong("bytes") + j.optLong("unknownBytes"),
             packets = j.optLong("written") + j.optLong("unknownWritten");
        String id = CaptureService.activeSession == null
            ? ""
            : CaptureService.activeSession.getAbsolutePath();
        if (!id.equals(rateSession) || bytes < rateBytes) {
            rateAt = 0;
            rateSession = id;
        }
        if (rateAt == 0) {
            rateAt = now;
            rateBytes = bytes;
            ratePackets = packets;
            rateText.setText("0 包/s · 0 B/s");
        } else if (now - rateAt >= 1000) {
            long dt = now - rateAt;
            rateText.setText(CaptureService.active
                    ? (packets - ratePackets) * 1000 / dt + " 包/s · "
                        + PacketStore.formatBytes((bytes - rateBytes) * 1000 / dt) + "/s"
                    : "已停止");
            rateAt = now;
            rateBytes = bytes;
            ratePackets = packets;
        }
        directionText.setText("↑ 发出  " + j.optLong("up") + "     ↓ 收到  " + j.optLong("down")
            + "\n归属未知已保留  " + j.optLong("unknownWritten") + " 包  ·  "
            + PacketStore.formatBytes(j.optLong("unknownBytes")) + "\n观测  "
            + j.optLong("received") + "     入队  " + j.optLong("accepted"));
        qualityText.setText("内核丢弃  "
            + (j.optBoolean("kernelStatsAvailable") ? j.optLong("kernelDrops") : "未采样")
            + "     队列丢弃  " + j.optLong("queueDrops") + "\n未知归属排除  "
            + j.optLong("unresolved") + "     明确过滤  " + j.optLong("filtered") + "\n协议过滤  "
            + j.optLong("protocolFiltered") + "     其他 UID  " + j.optLong("otherUidFiltered")
            + "\n截断  " + j.optLong("truncated") + "     保存后解析失败  "
            + j.optLong("parseFailedSaved") + "\n暂存超时  " + j.optLong("pendingExpired")
            + "     暂存满  " + j.optLong("pendingLimit") + "\n核心写盘错误  "
            + (j.optLong("writeFailure") > 0 ? "有" : "无") + "     等待写出  "
            + j.optLong("unsavedAccepted"));
        String err = j.optString("error");
        if (!j.optString("reportError").isEmpty())
            err += "\n完整性报告失败：" + j.optString("reportError");
        scope.setText(err.trim());
        scope.setVisibility(err.trim().isEmpty() ? View.GONE : View.VISIBLE);
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
                        Math.max(dp(120), Math.min((int) (bounds.height() * .92), available));
                    WindowManager.LayoutParams lp = d.getWindow().getAttributes();
                    if (lp.height != height)
                        d.getWindow().setLayout(
                            Math.min(bounds.width() - dp(16), dp(landscape() ? 1100 : 620)),
                            height);
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
            w.setLayout(Math.min(b.width() - dp(16), dp(landscape() ? 1100 : 620)),
                Boolean.TRUE.equals(w.getDecorView().getTag())
                    ? (int) (b.height() * .92)
                    : WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }
    private void refreshProcesses(Runnable listener) {
        if (listener != null)
            processListeners.add(listener);
        if (processBusy)
            return;
        processBusy = true;
        processError = null;
        processIo.execute(() -> {
            try {
                List<JSONObject> data = catalog.snapshot();
                List<AppEntry> all = new ArrayList<>();
                if (SystemClock.elapsedRealtime() - appCacheAt > 300000) {
                    appCache.clear();
                    appCacheAt = SystemClock.elapsedRealtime();
                }
                PackageManager pm = getPackageManager();
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
                    AppEntry cached = appCache.get(pkg);
                    if (cached == null) {
                        cached = new AppEntry();
                        cached.name = pkg;
                        try {
                            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                            cached.name = pm.getApplicationLabel(info).toString();
                            cached.icon = pm.getApplicationIcon(info);
                            cached.system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                            cached.game = info.category == ApplicationInfo.CATEGORY_GAME;
                        } catch (Exception ignored) {
                        }
                        if (appCache.size() < 512)
                            appCache.put(pkg, cached);
                    }
                    e.name = cached.name;
                    e.icon = cached.icon;
                    e.system = cached.system;
                    e.game = cached.game;
                    all.add(e);
                }
                all.sort((a, b) -> {
                    long x = prefs.getLong("recent:" + a.pkg, 0),
                         y = prefs.getLong("recent:" + b.pkg, 0);
                    if (x != y)
                        return Long.compare(y, x);
                    if (a.game != b.game)
                        return a.game ? -1 : 1;
                    if (a.system != b.system)
                        return a.system ? 1 : -1;
                    if (a.main != b.main)
                        return a.main ? -1 : 1;
                    return a.started != b.started ? Long.compare(b.started, a.started)
                                                  : Integer.compare(b.pid, a.pid);
                });
                post(() -> {
                    processCache = all;
                    finishProcesses();
                });
            } catch (Exception e) {
                post(() -> {
                    processError = e;
                    finishProcesses();
                });
            }
        });
    }
    private void finishProcesses() {
        processBusy = false;
        for (Runnable r : new ArrayList<>(processListeners)) r.run();
        processListeners.clear();
    }
    private void processDialog() {
        LinearLayout box = col();
        box.setPadding(dp(14), dp(14), dp(14), dp(8));
        TextView title = text("选择应用进程", 18, TEXT);
        box.addView(title);
        gap(box, 8);
        FrameLayout frame = new FrameLayout(this);
        ListView list = new ListView(this);
        TextView empty = text("首次读取进程…", 13, MUTED);
        empty.setGravity(Gravity.CENTER);
        frame.addView(list, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        box.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog d = dialog("", box);
        sizeDialog(d, true);
        final List<AppEntry> shown = new ArrayList<>(processCache);
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
            public View getView(int i, View v, ViewGroup parent) {
                AppEntry e = shown.get(i);
                LinearLayout line = row();
                line.setPadding(dp(4), dp(7), dp(4), dp(7));
                ImageView icon = new ImageView(WorkspaceUi.this);
                icon.setImageDrawable(
                    e.icon == null ? getDrawable(android.R.drawable.sym_def_app_icon) : e.icon);
                line.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
                LinearLayout labels = col();
                labels.setPadding(dp(10), 0, 0, 0);
                TextView name = text(e.name, 14, TEXT);
                name.setSingleLine();
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                labels.addView(name);
                TextView cmd = text(e.cmd + " · PID " + e.pid, 11, MUTED);
                cmd.setSingleLine();
                cmd.setEllipsize(android.text.TextUtils.TruncateAt.END);
                labels.addView(cmd);
                line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                line.addView(text(DisplayFormat.memory(e.rss), 12, ACCENT));
                return line;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((a, v, i, id) -> {
            if (CaptureService.active) {
                toast("当前会话目标已固定，请停止后切换");
                return;
            }
            AppEntry e = shown.get(i);
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
        refreshProcesses(() -> {
            if (!d.isShowing())
                return;
            if (processError != null) {
                empty.setText("读取失败，点击重试");
                empty.setOnClickListener(v -> {
                    d.dismiss();
                    processDialog();
                });
                toast(processError.getMessage());
                return;
            }
            int first = list.getFirstVisiblePosition();
            View v = list.getChildAt(0);
            int y = v == null ? 0 : v.getTop();
            shown.clear();
            shown.addAll(processCache);
            adapter.notifyDataSetChanged();
            list.setSelectionFromTop(first, y);
            empty.setText("没有可选的运行进程");
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
            public void beforeTextChanged(CharSequence s, int st, int count, int after) {}
            public void onTextChanged(CharSequence s, int st, int before, int count) {
                c.changed(s.toString());
            }
            public void afterTextChanged(android.text.Editable e) {}
        };
    }
    private ToolIcon tool(LinearLayout row, String kind, String title, Runnable action) {
        ToolIcon v = new ToolIcon(this, kind, title, action);
        row.addView(v, new LinearLayout.LayoutParams(dp(42), dp(42)));
        return v;
    }
    private void packetsPage() {
        if (selectedSession == null)
            selectedSession = CaptureService.activeSession;
        if (!Objects.equals(dataSession, selectedSession)) {
            dataSession = selectedSession;
            packetFilter = new PacketFilter();
            selected.clear();
            bookmarks.clear();
            records.clear();
            loadedIds.clear();
            dataView = 0;
            relatedKey = "";
            relatedLabel = "";
            relatedCount = -1;
            knownCount = -1;
        }
        LinearLayout page = col();
        body.addView(page);
        LinearLayout bar = landscape() ? row() : col();
        LinearLayout tabs = row(), tools = row();
        viewTabs.clear();
        viewBadges.clear();
        for (int i = 0; i < 3; i++) {
            final int n = i;
            LinearLayout segment = row();
            segment.setGravity(Gravity.CENTER);
            TextView t = text(new String[] {"全部", "已保存", "关联"}[i], 12, MUTED);
            t.setSingleLine();
            segment.addView(t);
            viewTabs.add(t);
            TextView badge = text("0", 10, TEXT);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(5), 0, dp(5), 0);
            badge.setBackground(background(0xff424f70, 9));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, dp(19));
            bl.leftMargin = dp(5);
            segment.addView(badge, bl);
            viewBadges.add(badge);
            tabs.addView(segment, new LinearLayout.LayoutParams(0, dp(36), 1));
            segment.setContentDescription(new String[] {"全部记录", "保存列表", "关联记录"}[i]);
            segment.setOnClickListener(v -> {
                if (n == 2 && relatedKey.isEmpty()) {
                    flowDialog();
                    return;
                }
                dataView = n;
                selected.clear();
                liveRefresh = n == 0;
                loadPackets(true);
            });
        }
        if (landscape())
            bar.addView(tabs, new LinearLayout.LayoutParams(0, dp(42), 1));
        else
            bar.addView(tabs, new LinearLayout.LayoutParams(-1, dp(36)));
        filterTool = tool(tools, "filter", "筛选", () -> filterDialog());
        searchTool = tool(tools, "search", "搜索文本或魔数", () -> searchDialog());
        saveTool = tool(tools, "save", "保存勾选的包", () -> saveSelection());
        compareTool = tool(tools, "compare", "比较两条已选记录", () -> compareSelected());
        tool(tools, "group", "连接、协议与长度分组", () -> flowDialog());
        tool(tools, "select", "选择操作", () -> selectionMenu());
        tool(tools, "more", "会话与显示操作", () -> dataMenu(tools));
        if (landscape())
            bar.addView(tools);
        else
            bar.addView(tools, new LinearLayout.LayoutParams(-1, dp(42)));
        page.addView(bar);
        FrameLayout area = new FrameLayout(this);
        packetList = new ListView(this);
        packetList.setDivider(new ColorDrawable(0x30515a70));
        packetList.setDividerHeight(dp(1));
        packetList.setClipToPadding(false);
        packetList.setFastScrollEnabled(false);
        TextView empty = text("尚无数据", 13, MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setOnClickListener(v -> loadPackets(true));
        area.addView(packetList, new FrameLayout.LayoutParams(-1, -1));
        area.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        packetList.setEmptyView(empty);
        page.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        dataInfo = text("", 11, MUTED);
        dataInfo.setSingleLine();
        dataInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
        dataInfo.setGravity(Gravity.CENTER_VERTICAL);
        dataInfo.setOnClickListener(v -> {
            if (pageFailed)
                loadPackets(false);
            else
                filterDialog();
        });
        page.addView(dataInfo, new LinearLayout.LayoutParams(-1, dp(25)));
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
            public boolean hasStableIds() {
                return true;
            }
            public View getView(int i, View convert, ViewGroup parent) {
                PacketRow h;
                if (convert == null) {
                    h = new PacketRow();
                    LinearLayout line = row();
                    line.setMinimumHeight(dp(46));
                    line.setPadding(0, dp(2), dp(5), dp(2));
                    h.check = new CheckBox(WorkspaceUi.this);
                    h.check.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
                    h.check.setFocusable(false);
                    line.addView(h.check, new LinearLayout.LayoutParams(dp(40), dp(44)));
                    h.labels = landscape() ? row() : col();
                    h.meta = text("", landscape() ? 11 : 12, ACCENT);
                    h.route = text("", landscape() ? 12 : 11, TEXT);
                    h.route.setSingleLine();
                    h.route.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                    h.meta.setSingleLine();
                    if (landscape()) {
                        h.labels.addView(h.meta, new LinearLayout.LayoutParams(dp(155), -2));
                        h.labels.addView(h.route, new LinearLayout.LayoutParams(0, -2, 1));
                    } else {
                        h.labels.addView(h.meta);
                        h.labels.addView(h.route);
                    }
                    line.addView(h.labels, new LinearLayout.LayoutParams(0, -2, 1));
                    h.size = text("", 11, MUTED);
                    h.size.setGravity(Gravity.RIGHT);
                    if (landscape())
                        line.addView(h.size, new LinearLayout.LayoutParams(dp(76), -2));
                    line.setTag(h);
                    convert = line;
                } else
                    h = (PacketRow) convert.getTag();
                PacketStore.Record r = records.get(i);
                boolean checked = selected.containsKey(r.id);
                String alias = bookmarks.get(r.id);
                h.check.setChecked(checked);
                h.check.setContentDescription("选择记录 " + r.id);
                h.check.setOnClickListener(v -> toggle(r));
                h.meta.setText((r.dir == 1          ? "↑ "
                                       : r.dir == 0 ? "↓ "
                                                    : "? ")
                    + r.protocol() + " · " + r.time()
                    + (landscape() ? "" : "  #" + r.id + " · " + r.caplen + " B")
                    + (alias != null ? " ★" : ""));
                h.meta.setTextColor(r.dir == 1 ? 0xff80d5c2 : ACCENT);
                h.route.setText(
                    (alias != null && !alias.isEmpty() ? alias + " · " : "") + r.route());
                h.size.setText(r.caplen + " B  #" + r.id);
                convert.setBackgroundColor(checked ? 0xe52a344a : SURFACE);
                return convert;
            }
        };
        packetList.setAdapter(packetAdapter);
        packetList.setOnItemClickListener((a, v, p, id) -> detail(records.get(p)));
        packetList.setOnItemLongClickListener((a, v, p, id) -> {
            packetMenu(records.get(p));
            return true;
        });
        packetList.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int state) {
                scrollBusy = state != SCROLL_STATE_IDLE;
                if (scrollBusy)
                    liveRefresh = false;
                else
                    maybeLoad();
            }
            public void onScroll(AbsListView v, int first, int visible, int total) {
                if (visible > 0)
                    maybeLoad();
            }
            void maybeLoad() {
                if (queryBusy || pageFailed || records.isEmpty())
                    return;
                int first = packetList.getFirstVisiblePosition(),
                    last = packetList.getLastVisiblePosition();
                if (hasNewer && first < 3)
                    loadWindow(false, true);
                else if (!exhausted && last >= records.size() - 12)
                    loadPackets(false);
            }
        });
        updateDataTools();
        if (selectedSession != null)
            loadPackets(true);
    }
    private static final class PacketRow {
        CheckBox check;
        LinearLayout labels;
        TextView meta, route, size;
    }
    private void toggle(PacketStore.Record r) {
        liveRefresh = false;
        if (selected.containsKey(r.id))
            selected.remove(r.id);
        else {
            if (selected.size() >= 512) {
                toast("一次最多勾选 512 条，可分批保存");
                return;
            }
            selected.put(r.id, r);
        }
        packetAdapter.notifyDataSetChanged();
        updateDataTools();
    }
    private void updateDataTools() {
        if (tab != 1 || dataInfo == null)
            return;
        long total = selectedSession == null ? 0 : PacketStore.count(selectedSession);
        long[] counts = {total, bookmarks.size(), relatedCount};
        for (int i = 0; i < viewTabs.size(); i++) {
            TextView v = viewTabs.get(i);
            v.setTextColor(i == dataView ? TEXT : MUTED);
            ((View) v.getParent())
                .setBackground(background(i == dataView ? 0xff303b55 : 0x0010121a, 8));
            TextView badge = viewBadges.get(i);
            badge.setText("" + counts[i]);
            badge.setVisibility(counts[i] < 0 ? View.GONE : View.VISIBLE);
        }
        saveTool.setEnabled(!selected.isEmpty());
        compareTool.setEnabled(selected.size() == 2);
        filterTool.active = packetFilter.protocol != 0 || packetFilter.direction >= 0
            || packetFilter.readable != 0 || packetFilter.minBytes > 0
            || packetFilter.maxBytes < Integer.MAX_VALUE;
        searchTool.active = !packetFilter.text.isEmpty() || packetFilter.magic != null;
        filterTool.invalidate();
        searchTool.invalidate();
        saveTool.invalidate();
        compareTool.invalidate();
        String f = (packetFilter.protocol == 0           ? "全部协议"
                           : packetFilter.protocol == 6  ? "TCP"
                           : packetFilter.protocol == 17 ? "UDP"
                                                         : "ICMP")
            + " · "
            + (packetFilter.direction < 0         ? "双向"
                    : packetFilter.direction == 1 ? "发出"
                    : packetFilter.direction == 0 ? "收到"
                                                  : "方向未知");
        if (packetFilter.readable > 0)
            f += " · " + (packetFilter.readable == 1 ? "可读文本" : "非文本");
        if (searchTool.active)
            f += " · 搜索中";
        dataInfo.setText(
            (selectedSession != null && selectedSession.getName().equals("unattributed")
                    ? "归属未知 · "
                    : "")
            + (queryBusy         ? "读取中 · "
                    : pageFailed ? "读取失败，点击重试 · "
                                 : "")
            + (selected.isEmpty() ? "已载入 " + records.size() + " 条"
                                  : "已选 " + selected.size() + " 条")
            + (dataView == 2 ? " · " + relatedLabel : "") + " · " + f
            + (exhausted ? " · 已到末尾" : ""));
    }
    private void loadPackets(boolean reset) {
        loadWindow(reset, false);
    }
    private void loadWindow(boolean reset, boolean newer) {
        if (tab != 1 || selectedSession == null)
            return;
        if (queryBusy && !reset)
            return;
        if (!reset && !newer && exhausted)
            return;
        final int g = ++generation;
        queryBusy = true;
        pageFailed = false;
        lastRefresh = System.currentTimeMillis();
        File session = selectedSession;
        PacketFilter f = packetFilter.copy();
        int view = dataView;
        if (view == 2)
            f.groupKey = relatedKey;
        long start = reset ? -1 : newer ? records.get(0).id : cursor;
        int first = packetList.getFirstVisiblePosition();
        View child = packetList.getChildAt(0);
        int y = child == null ? 0 : child.getTop();
        updateDataTools();
        io.execute(() -> {
            try {
                Map<Long, String> notes = PacketNotes.read(session);
                if (view == 1)
                    f.ids = new HashSet<>(notes.keySet());
                PacketStore.Query q = newer
                    ? PacketStore.newer(session, start, f,
                          () -> generation != g || Thread.currentThread().isInterrupted())
                    : PacketStore.query(session, start, f,
                          () -> generation != g || Thread.currentThread().isInterrupted());
                post(() -> {
                    if (generation != g || tab != 1)
                        return;
                    queryBusy = false;
                    bookmarks = notes;
                    knownCount = q.total;
                    if (reset) {
                        records.clear();
                        loadedIds.clear();
                        exhausted = false;
                        hasNewer = false;
                    }
                    int added = 0;
                    if (newer) {
                        Collections.reverse(q.records);
                        for (int i = q.records.size() - 1; i >= 0; i--) {
                            PacketStore.Record r = q.records.get(i);
                            if (loadedIds.add(r.id)) {
                                records.add(0, r);
                                added++;
                            }
                        }
                        hasNewer = q.next < q.total;
                    } else {
                        for (PacketStore.Record r : q.records)
                            if (loadedIds.add(r.id))
                                records.add(r);
                        cursor = q.next;
                        exhausted = cursor < 0;
                    }
                    int removed = 0;
                    if (records.size() > 1536) {
                        removed = records.size() - 1536;
                        if (newer) {
                            for (PacketStore.Record r : records.subList(1536, records.size()))
                                loadedIds.remove(r.id);
                            records.subList(1536, records.size()).clear();
                            cursor = records.get(records.size() - 1).id - 2;
                            exhausted = cursor < 0;
                        } else {
                            for (PacketStore.Record r : records.subList(0, removed))
                                loadedIds.remove(r.id);
                            records.subList(0, removed).clear();
                            hasNewer = true;
                        }
                    }
                    packetAdapter.notifyDataSetChanged();
                    if (reset)
                        packetList.setSelection(0);
                    else
                        packetList.setSelectionFromTop(
                            Math.max(0, first + (newer ? added : -removed)), y);
                    TextView empty =
                        (TextView) ((FrameLayout) packetList.getParent()).getChildAt(1);
                    empty.setText(exhausted ? view == 1 ? "尚无保存的匹配记录" : "没有匹配记录"
                                            : "正在查找匹配记录…");
                    updateDataTools();
                    if (!newer && !exhausted && records.size() < 15)
                        ui.postDelayed(() -> {
                            if (generation == g)
                                loadPackets(false);
                        }, 40);
                });
            } catch (Exception e) {
                post(() -> {
                    if (generation != g)
                        return;
                    queryBusy = false;
                    pageFailed = true;
                    updateDataTools();
                    error("读取记录失败", e);
                });
            }
        });
    }
    private void saveSelection() {
        if (selected.isEmpty())
            return;
        changeNotes(new ArrayList<>(selected.keySet()), false, null);
    }
    private void changeNotes(Collection<Long> ids, boolean remove, String name) {
        if (selectedSession == null)
            return;
        File f = selectedSession;
        io.execute(() -> {
            try {
                Map<Long, String> notes = PacketNotes.edit(f, ids, remove, name);
                post(() -> {
                    if (!f.equals(selectedSession))
                        return;
                    bookmarks = notes;
                    selected.clear();
                    loadPackets(true);
                    toast(remove ? "已移出保存列表，原包保留" : "已保存");
                });
            } catch (Exception e) {
                post(() -> error("更新保存列表失败", e));
            }
        });
    }
    private void selectionMenu() {
        alert()
            .setTitle("选择操作")
            .setItems(new String[] {"全选当前载入记录", "取消所有勾选", "移出保存列表"},
                (d, n) -> {
                    if (n == 2) {
                        if (selected.isEmpty())
                            toast("请先勾选记录");
                        else
                            changeNotes(new ArrayList<>(selected.keySet()), true, null);
                        return;
                    }
                    selected.clear();
                    if (n == 0) {
                        for (PacketStore.Record r : records) {
                            if (selected.size() == 512)
                                break;
                            selected.put(r.id, r);
                        }
                        liveRefresh = false;
                    }
                    packetAdapter.notifyDataSetChanged();
                    updateDataTools();
                })
            .setNegativeButton("取消", null)
            .show();
    }
    private void packetMenu(PacketStore.Record r) {
        List<String> names = new ArrayList<>(
            Arrays.asList("查看详情", "查看同一连接", "保存到列表", "复制通信地址"));
        if (bookmarks.containsKey(r.id)) {
            names.add("重命名收藏");
            names.add("移出保存列表");
        }
        alert()
            .setTitle("记录 #" + r.id)
            .setItems(names.toArray(new String[0]),
                (d, n) -> {
                    if (n == 0)
                        detail(r);
                    else if (n == 1) {
                        relatedCount = -1;
                        relatedKey = PacketFilter.group(r, 0);
                        relatedLabel = r.protocol() + " · " + r.route();
                        dataView = 2;
                        selected.clear();
                        loadPackets(true);
                    } else if (n == 2)
                        changeNotes(Collections.singleton(r.id), false, null);
                    else if (n == 3) {
                        getSystemService(android.content.ClipboardManager.class)
                            .setPrimaryClip(ClipData.newPlainText("通信地址", r.route()));
                        toast("已复制");
                    } else if (n == 4)
                        renameBookmark(r);
                    else
                        changeNotes(Collections.singleton(r.id), true, null);
                })
            .setNegativeButton("取消", null)
            .show();
    }
    private void renameBookmark(PacketStore.Record r) {
        EditText name = input("收藏名称");
        name.setText(bookmarks.getOrDefault(r.id, ""));
        LinearLayout box = col();
        box.setPadding(dp(16), dp(16), dp(16), dp(12));
        box.addView(text("重命名收藏 #" + r.id, 17, TEXT));
        gap(box, 8);
        box.addView(name);
        Dialog d = dialog("", box);
        fullButton(box, "保存", true, () -> {
            String value = name.getText().toString().trim();
            if (value.length() > 100) {
                name.setError("最多 100 字");
                return;
            }
            changeNotes(Collections.singleton(r.id), false, value);
            d.dismiss();
        });
        sizeDialog(d, false);
    }
    private void searchDialog() {
        LinearLayout box = col();
        box.setPadding(dp(16), dp(16), dp(16), dp(12));
        box.addView(text("搜索载荷", 18, TEXT));
        gap(box, 10);
        Spinner mode =
            spinner(new String[] {"文本与地址", "十六进制：载荷任意处", "十六进制：载荷开头"},
                packetFilter.magic == null ? 0
                    : packetFilter.prefix  ? 2
                                           : 1);
        box.addView(mode);
        EditText value = input("例如 login 或 01 AB FF");
        String h = "";
        if (packetFilter.magic != null)
            for (byte b : packetFilter.magic) h += String.format(Locale.ROOT, "%02X ", b & 255);
        value.setText(packetFilter.magic == null ? packetFilter.text : h.trim());
        box.addView(value);
        Dialog d = dialog("", box);
        fullButton(box, "应用搜索", true, () -> {
            String v = value.getText().toString().trim();
            byte[] bytes = null;
            if (mode.getSelectedItemPosition() > 0 && !v.isEmpty()) {
                String hex = v.replaceAll("\\s+", "");
                if (hex.length() % 2 != 0 || !hex.matches("[0-9a-fA-F]+")) {
                    value.setError("请输入成对十六进制字节");
                    return;
                }
                if (hex.length() > 4096) {
                    value.setError("魔数最多 2048 字节");
                    return;
                }
                bytes = new byte[hex.length() / 2];
                for (int i = 0; i < bytes.length; i++)
                    bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            packetFilter.magic = bytes;
            packetFilter.prefix = mode.getSelectedItemPosition() == 2;
            packetFilter.text = mode.getSelectedItemPosition() == 0 ? v : "";
            liveRefresh = false;
            selected.clear();
            d.dismiss();
            loadPackets(true);
        });
        fullButton(box, "清除搜索", false, () -> {
            packetFilter.text = "";
            packetFilter.magic = null;
            d.dismiss();
            loadPackets(true);
        });
        sizeDialog(d, false);
    }
    private Spinner spinner(String[] values, int initial) {
        Spinner spinner = new Spinner(this) {
            @Override
            public boolean performClick() {
                alert()
                    .setTitle("选择条件")
                    .setSingleChoiceItems(values, getSelectedItemPosition(),
                        (d, n) -> {
                            setSelection(n);
                            d.dismiss();
                        })
                    .setNegativeButton("取消", null)
                    .show();
                return true;
            }
        };
        ArrayAdapter<String> a =
            new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(a);
        spinner.setSelection(initial);
        spinner.setMinimumHeight(dp(44));
        return spinner;
    }
    private void filterDialog() {
        LinearLayout box = col();
        box.setPadding(dp(16), dp(16), dp(16), dp(10));
        box.addView(text("显示筛选", 18, TEXT));
        Spinner proto = spinner(new String[] {"全部协议", "TCP", "UDP", "ICMP", "ICMPv6"},
            packetFilter.protocol == 6        ? 1
                : packetFilter.protocol == 17 ? 2
                : packetFilter.protocol == 1  ? 3
                : packetFilter.protocol == 58 ? 4
                                              : 0);
        Spinner direction = spinner(new String[] {"全部方向", "发出", "收到", "方向未知"},
            packetFilter.direction < 0        ? 0
                : packetFilter.direction == 1 ? 1
                : packetFilter.direction == 0 ? 2
                                              : 3);
        Spinner readable = spinner(
            new String[] {"全部内容", "可读文本载荷", "非文本或无载荷"}, packetFilter.readable);
        box.addView(proto);
        box.addView(direction);
        box.addView(readable);
        LinearLayout sizes = row();
        EditText min = input("最小字节"), max = input("最大字节");
        min.setInputType(2);
        max.setInputType(2);
        if (packetFilter.minBytes > 0)
            min.setText("" + packetFilter.minBytes);
        if (packetFilter.maxBytes < Integer.MAX_VALUE)
            max.setText("" + packetFilter.maxBytes);
        sizes.addView(min, new LinearLayout.LayoutParams(0, -2, 1));
        sizes.addView(max, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(sizes);
        Dialog d = dialog("", box);
        fullButton(box, "应用筛选", true, () -> {
            try {
                int lo = min.getText().length() == 0 ? 0
                                                     : Integer.parseInt(min.getText().toString()),
                    hi = max.getText().length() == 0 ? Integer.MAX_VALUE
                                                     : Integer.parseInt(max.getText().toString());
                if (lo > hi)
                    throw new NumberFormatException();
                packetFilter.protocol =
                    new int[] {0, 6, 17, 1, 58}[proto.getSelectedItemPosition()];
                packetFilter.direction =
                    new int[] {-1, 1, 0, 255}[direction.getSelectedItemPosition()];
                packetFilter.readable = readable.getSelectedItemPosition();
                packetFilter.minBytes = lo;
                packetFilter.maxBytes = hi;
                selected.clear();
                liveRefresh = false;
                d.dismiss();
                loadPackets(true);
            } catch (Exception e) {
                min.setError("请填写有效的字节范围");
            }
        });
        fullButton(box, "重置筛选与搜索", false, () -> {
            packetFilter = new PacketFilter();
            selected.clear();
            d.dismiss();
            loadPackets(true);
        });
        sizeDialog(d, false);
    }
    private void compareSelected() {
        if (selected.size() != 2)
            return;
        List<PacketStore.Record> pair = new ArrayList<>(selected.values());
        pair.sort(Comparator.comparingLong(r -> r.id));
        LinearLayout box = col();
        box.setPadding(dp(12), dp(10), dp(12), dp(8));
        LinearLayout head = row();
        TextView title = text("载荷比较  #" + pair.get(0).id + " → #" + pair.get(1).id, 16, TEXT);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(head);
        TextView data = text("读取中…", 12, TEXT);
        data.setTypeface(Typeface.MONOSPACE);
        data.setTextIsSelectable(true);
        ScrollView sc = scroll();
        sc.addView(data);
        box.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog d = dialog("", box);
        tool(head, "close", "关闭", () -> d.dismiss());
        sizeDialog(d, true);
        io.execute(() -> {
            try {
                byte[] a = PacketStore.payload(pair.get(0), PacketStore.bytes(pair.get(0))),
                       b = PacketStore.payload(pair.get(1), PacketStore.bytes(pair.get(1)));
                String diff = PacketStore.diff(a, b);
                post(() -> {
                    if (d.isShowing())
                        data.setText(diff);
                });
            } catch (Exception e) {
                post(() -> data.setText("比较失败：" + e.getMessage()));
            }
        });
    }
    private void detail(PacketStore.Record r) {
        liveRefresh = false;
        LinearLayout box = col();
        box.setPadding(dp(10), dp(4), dp(10), dp(6));
        LinearLayout head = row();
        TextView title =
            text("#" + r.id + " · " + r.protocol() + " · " + r.time() + " · " + r.caplen + " B", 13,
                TEXT);
        title.setSingleLine();
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(head);
        TextView route = text(r.route(), 11, MUTED);
        route.setSingleLine();
        route.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        box.addView(route);
        LinearLayout actions = row();
        box.addView(actions);
        ScrollView sc = scroll();
        TextView content = text("读取中…", 12, TEXT);
        content.setTextIsSelectable(true);
        content.setTypeface(Typeface.MONOSPACE);
        sc.addView(content);
        HexDumpView hex = new HexDumpView(this);
        box.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog d = dialog("", box);
        tool(head, "close", "关闭详情", () -> d.dismiss());
        sizeDialog(d, true);
        io.execute(() -> {
            try {
                byte[] raw = PacketStore.bytes(r), payload = PacketStore.payload(r, raw);
                String readable = PacketStore.readable(payload);
                post(() -> {
                    if (!d.isShowing())
                        return;
                    hex.setBytes(raw);
                    sc.removeAllViews();
                    sc.addView(hex);
                    for (String label : new String[] {"原始字节", "可读内容", "网络字段", "保存"}) {
                        Button b = compactButton(label, () -> {
                            if (label.equals("保存")) {
                                changeNotes(Collections.singleton(r.id), false, null);
                                return;
                            }
                            sc.removeAllViews();
                            if (label.equals("原始字节"))
                                sc.addView(hex);
                            else {
                                sc.addView(content);
                                content.setText(label.equals("可读内容") ? readable
                                                                         : r.protocol() + " · IPv"
                                            + r.version + "\n" + r.route() + "\n方向："
                                            + (r.dir == 1        ? "发出"
                                                    : r.dir == 0 ? "收到"
                                                                 : "未知")
                                            + "\n接口索引：" + r.iface + "\n捕获 / 原始："
                                            + r.caplen + " / " + r.wirelen + " B\n载荷："
                                            + r.payloadLen + " B\nUID：" + r.uid + "\n归属："
                                            + ((r.flags & 8) != 0 ? "未知（独立保留）"
                                                                  : "会话抓取范围")
                                            + "\n分片：" + ((r.flags & 1) != 0)
                                            + "\n解析失败标记：" + ((r.flags & 4) != 0));
                            }
                            sc.scrollTo(0, 0);
                        });
                        actions.addView(b, new LinearLayout.LayoutParams(0, dp(36), 1));
                    }
                });
            } catch (Exception e) {
                post(() -> content.setText("读取失败：" + e.getMessage()));
            }
        });
    }
    private void dataMenu(View anchor) {
        String[] names = {"返回最新记录", "停止跟随最新", "查看归属未知 / 返回主会话", "完整性报告",
            "导出当前会话"};
        names[1] = liveRefresh ? "停止跟随最新" : "跟随最新记录";
        alert()
            .setTitle("数据操作")
            .setItems(names,
                (d, n) -> {
                    if (n == 0) {
                        loadPackets(true);
                        return;
                    }
                    if (n == 1) {
                        liveRefresh = !liveRefresh;
                        if (liveRefresh) {
                            dataView = 0;
                            selected.clear();
                            loadPackets(true);
                        }
                        toast(liveRefresh ? "正在跟随最新" : "保持当前浏览位置");
                        return;
                    }
                    if (selectedSession == null) {
                        toast("请先选择会话");
                        return;
                    }
                    if (n == 2) {
                        File candidate = selectedSession.getName().equals("unattributed")
                            ? selectedSession.getParentFile()
                            : new File(selectedSession, "unattributed");
                        if (!new File(candidate, "packets.idx").isFile()) {
                            toast("此会话没有独立保留的未知归属包");
                            return;
                        }
                        selectedSession = candidate;
                        showTab(1);
                    } else if (n == 3)
                        reportDialog(selectedSession);
                    else
                        exportDialog(selectedSession);
                })
            .setNegativeButton("取消", null)
            .show();
    }
    private void flowDialog() {
        if (selectedSession == null) {
            toast("请先选择会话");
            return;
        }
        alert()
            .setTitle("组织数据")
            .setItems(
                new String[] {"通信连接", "协议分布", "包长度分布"}, (d, kind) -> groupDialog(kind))
            .setNegativeButton("取消", null)
            .show();
    }
    private void groupDialog(int kind) {
        File f = selectedSession;
        LinearLayout box = col();
        box.setPadding(dp(12), dp(8), dp(12), dp(6));
        LinearLayout head = row();
        TextView title = text(new String[] {"通信连接", "协议分布", "包长度分布"}[kind], 17, TEXT);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(head);
        TextView hint = text("正在汇总已捕获记录…", 11, MUTED);
        box.addView(hint);
        ListView list = new ListView(this);
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog d = dialog("", box);
        tool(head, "close", "关闭分组", () -> d.dismiss());
        sizeDialog(d, true);
        io.execute(() -> {
            try {
                List<PacketStore.Group> groups = PacketStore.groups(
                    f, kind, () -> !d.isShowing() || Thread.currentThread().isInterrupted());
                List<String> labels = new ArrayList<>();
                for (PacketStore.Group g : groups)
                    labels.add(g.label + "\n" + g.count + " 包 · "
                        + PacketStore.formatBytes(g.bytes) + " · ↑ " + g.up + " ↓ " + g.down + " ? "
                        + g.unknown);
                post(() -> {
                    if (!d.isShowing())
                        return;
                    hint.setText(groups.size() + " 个分组 · 点击查看关联记录");
                    list.setAdapter(simpleAdapter(labels));
                    list.setOnItemClickListener((a, v, p, id) -> {
                        PacketStore.Group g = groups.get(p);
                        if (g.key.isEmpty())
                            return;
                        relatedCount = g.count;
                        relatedKey = g.key;
                        relatedLabel = g.label;
                        dataView = 2;
                        selected.clear();
                        liveRefresh = false;
                        d.dismiss();
                        loadPackets(true);
                    });
                    list.setOnItemLongClickListener((a, v, p, id) -> {
                        PacketStore.Group g = groups.get(p);
                        getSystemService(android.content.ClipboardManager.class)
                            .setPrimaryClip(ClipData.newPlainText("数据分组", g.label));
                        toast("已复制分组信息");
                        return true;
                    });
                });
            } catch (Exception e) {
                post(() -> hint.setText("读取失败：" + e.getMessage()));
            }
        });
    }
    private void reportDialog(File f) {
        if (f == null) {
            toast("请先选择会话");
            return;
        }
        if (CaptureService.active
            && (f.equals(CaptureService.activeSession)
                || f.getParentFile().equals(CaptureService.activeSession))) {
            toast("请先停止并保存，再检查完整性");
            return;
        }
        LinearLayout box = col();
        box.setPadding(dp(12), dp(8), dp(12), dp(6));
        LinearLayout head = row();
        head.addView(text("完整性报告", 17, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(head);
        ScrollView sc = scroll();
        TextView value = text("检查中…", 13, TEXT);
        value.setTextIsSelectable(true);
        sc.addView(value);
        box.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog d = dialog("", box);
        tool(head, "close", "关闭报告", () -> d.dismiss());
        sizeDialog(d, true);
        io.execute(() -> {
            try {
                File report = new File(f, "integrity.txt");
                if (!report.isFile())
                    IntegrityReport.generate(f, PacketStore.manifest(f), () -> !d.isShowing());
                String reportText = PacketStore.readText(report);
                post(() -> {
                    if (d.isShowing())
                        value.setText(reportText);
                });
            } catch (Exception e) {
                post(() -> value.setText("检查失败：" + e.getMessage()));
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
        if (CaptureService.active
            && (session.equals(CaptureService.activeSession)
                || session.getParentFile().equals(CaptureService.activeSession))) {
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
        fullButton(advanced, "抓取范围：" + (prefs.getInt("uid", -1) < 0 ? "整机 IP" : "选中应用"),
            false, () -> {
                if (CaptureService.active) {
                    toast("请先停止后切换抓取范围");
                    return;
                }
                alert()
                    .setTitle("抓取范围")
                    .setItems(new String[] {"选择应用进程", "整机 IP 流量"},
                        (d, n) -> {
                            if (n == 0)
                                processDialog();
                            else {
                                prefs.edit()
                                    .putInt("uid", -1)
                                    .putInt("pid", -1)
                                    .remove("rss")
                                    .remove("package")
                                    .putString("label", "整机流量")
                                    .putBoolean("scopeChosen", true)
                                    .apply();
                                showTab(3);
                            }
                        })
                    .setNegativeButton("取消", null)
                    .show();
            });
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
        Switch unknown = new Switch(this);
        unknown.setText("研究模式：独立保留归属未知包");
        unknown.setTextColor(TEXT);
        unknown.setChecked(prefs.getBoolean("keepUnknown", false));
        unknown.setOnCheckedChangeListener((b, v) -> {
            prefs.edit().putBoolean("keepUnknown", v).apply();
            toast(v ? "下次抓包独立保留未知包，可能含其他应用流量" : "下次抓包排除未知归属包");
        });
        gap(advanced, 10);
        advanced.addView(unknown);
        LinearLayout appearance = card(page, "界面与交互");
        TextView alphaLabel = text("背景不透明度 " + prefs.getInt("opacity", 86) + "%", 14, TEXT);
        appearance.addView(alphaLabel);
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(50);
        opacity.setProgress(prefs.getInt("opacity", 86) - 45);
        appearance.addView(opacity, new LinearLayout.LayoutParams(-1, dp(44)));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
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
                    for (File f : dirs) labels.add((f.isDirectory() ? "▸  " : "▤  ") + f.getName());
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
