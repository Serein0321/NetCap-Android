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
    private ThemePalette palette;
    private int BG, SURFACE, EDGE, TEXT, MUTED, ACCENT, AMBER, RED;
    private void loadPalette() {
        palette = ThemeUi.palette(this);
        BG = palette.bg;
        SURFACE = palette.surface;
        EDGE = palette.edge;
        TEXT = palette.text;
        MUTED = palette.muted;
        ACCENT = palette.accent;
        AMBER = palette.warning;
        RED = palette.error;
        android.content.res.Resources.Theme fresh = getResources().newTheme();
        fresh.applyStyle(ThemeUi.overlay(this), true);
        getTheme().setTo(fresh);
    }
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
    private final Map<Dialog, Runnable> dialogBackActions = new HashMap<>();
    private final Set<Dialog> fixedDialogs = new HashSet<>();
    private final Map<Dialog, Integer> editorHeights = new HashMap<>();
    private final Set<Dialog> fitting = new HashSet<>();
    private final Set<File> historySelection = new LinkedHashSet<>();
    private boolean historyManaging;
    private File browserRoot, browserDir;
    private String browserBase = "";
    private final List<File> fileClipboard = new ArrayList<>();
    private boolean clipboardMove;
    private List<WorkspaceFiles.Item> fileEntries = new ArrayList<>();
    private int historyEpoch;
    private FileActionMenu fileActionMenu;
    private static final class FileActionMenu {
        final Dialog dialog;
        final View target, content;
        final Drawable originalBackground;
        final int x, y;
        FileActionMenu(Dialog dialog, View target, View content, int x, int y) {
            this.dialog = dialog;
            this.target = target;
            this.content = content;
            this.x = x;
            this.y = y;
            originalBackground = target.getBackground();
        }
    }
    private volatile int relatedEpoch;

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
            ThemeUi.overlay(owner));
        service = owner;
        prefs = getSharedPreferences("settings", 0);
        loadPalette();
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
        relatedEpoch++;
        historyEpoch++;
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
            dismissFileMenu();
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
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            w.setDecorFitsSystemWindows(false);
            WindowManager.LayoutParams frame = w.getAttributes();
            frame.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            frame.setFitInsetsTypes(0);
            frame.gravity = Gravity.TOP | Gravity.LEFT;
            frame.x = frame.y = 0;
            w.setAttributes(frame);
            w.setWindowAnimations(0);
            w.setBackgroundDrawable(background(palette.modal, 22));
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
        v.setTextColor(primary ? palette.onAccent : TEXT);
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
        shell.setBackgroundColor(
            (prefs.getInt("opacity", 86) * 255 / 100 << 24) | (palette.workspace & 0xffffff));
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
        processIcon.setBackground(background(palette.active, 12));
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
        divider.setBackgroundColor(EDGE);
        shell.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        gap(shell, 4);
        body = new FrameLayout(this);
        shell.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        allowScreenCapture();
        showTab(tab);
        shell.requestFocus();
    }
    private void showTab(int index) {
        dismissFileMenu();
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
                t.setBackground(background(palette.active, 8));
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
            service.collapseWorkspace();
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
            w.setBackgroundDrawable(background(palette.modal, 22));
        }
        return d;
    }
    // 所有子弹窗在 show 前设为悬浮窗口，保持模态触摸与系统 IME 的正常关系。
    private <T extends Dialog> T track(T d) {
        if (!openDialogs.contains(d)) {
            configureWindow(d.getWindow(), true);
            openDialogs.add(d);
            d.setOnDismissListener(x -> {
                restoreFileMenu(d);
                openDialogs.remove(d);
                dialogBackActions.remove(d);
                fixedDialogs.remove(d);
                editorHeights.remove(d);
                fitting.remove(d);
            });
            View decor = d.getWindow().getDecorView();
            android.window.OnBackInvokedCallback callback = () -> back(d, () -> {
                Runnable custom = dialogBackActions.get(d);
                if (custom != null)
                    custom.run();
                else if (!fixedDialogs.contains(d))
                    d.cancel();
            });
            decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View v) {
                    ui.post(() -> {
                        if (d.isShowing())
                            fitDialog(d);
                    });
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
                if (d.isShowing())
                    fitDialog(d, in);
                return in;
            });
            d.setOnKeyListener((dlg, key, event) -> {
                if (key == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    back(d, () -> {
                        Runnable custom = dialogBackActions.get(d);
                        if (custom != null)
                            custom.run();
                        else if (!fixedDialogs.contains(d))
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
    private void sizeEditor(Dialog d, int heightDp) {
        editorHeights.put(d, dp(heightDp));
        sizeDialog(d, false);
    }
    private void fitDialog(Dialog d) {
        fitDialog(d, d.getWindow().getDecorView().getRootWindowInsets());
    }
    private void fitDialog(Dialog d, WindowInsets in) {
        if (!d.isShowing() || fitting.contains(d))
            return;
        fitting.add(d);
        try {
            Window w = d.getWindow();
            WindowMetrics metrics = getWindowManager().getCurrentWindowMetrics();
            android.graphics.Rect bounds = metrics.getBounds();
            android.graphics.Insets safe = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int ime = in == null ? 0 : in.getInsets(WindowInsets.Type.ime()).bottom;
            if (fileActionMenu != null && fileActionMenu.dialog == d) {
                fitFileMenu(fileActionMenu, bounds, safe, ime);
                return;
            }
            int available =
                Math.max(1, bounds.height() - safe.top - Math.max(safe.bottom, ime) - dp(16));
            ModalGeometry limit =
                ModalGeometry.fit(bounds.width(), bounds.height(), safe.left, safe.top, safe.right,
                    safe.bottom, ime, dp(8), dp(landscape() ? 1000 : 620), available);
            View title = w.getDecorView().findViewWithTag("modal-header");
            if (title != null)
                title.setVisibility(ime > 0 && available < dp(180) ? View.GONE : View.VISIBLE);
            int wanted;
            if (editorHeights.containsKey(d))
                wanted = editorHeights.get(d);
            else if (Boolean.TRUE.equals(w.getDecorView().getTag()))
                wanted = available;
            else {
                w.getDecorView().measure(
                    View.MeasureSpec.makeMeasureSpec(limit.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST));
                wanted = Math.max(1, w.getDecorView().getMeasuredHeight());
            }
            ModalGeometry frame =
                ModalGeometry.fit(bounds.width(), bounds.height(), safe.left, safe.top, safe.right,
                    safe.bottom, ime, dp(8), dp(landscape() ? 1000 : 620), wanted);
            WindowManager.LayoutParams lp = w.getAttributes();
            if (lp.width != frame.width || lp.height != frame.height || lp.x != frame.x
                || lp.y != frame.y) {
                lp.width = frame.width;
                lp.height = frame.height;
                lp.x = frame.x;
                lp.y = frame.y;
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                w.setAttributes(lp);
            }
        } finally {
            fitting.remove(d);
        }
    }
    private LinearLayout modalHead(LinearLayout box, String title) {
        LinearLayout header = row();
        header.setTag("modal-header");
        TextView t = text(title, 17, TEXT);
        t.setTypeface(null, Typeface.BOLD);
        t.setSingleLine();
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(header);
        return header;
    }
    private LinearLayout modalBody(LinearLayout box) {
        ScrollView sc = scroll();
        LinearLayout content = col();
        sc.addView(content);
        box.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        return content;
    }
    private void action(LinearLayout row, String title, boolean primary, Runnable run) {
        Button b = compactButton(title, run);
        if (primary)
            b.setBackground(background(ACCENT, 10));
        if (primary)
            b.setTextColor(palette.onAccent);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
        lp.setMargins(dp(2), dp(3), dp(2), 0);
        row.addView(b, lp);
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
            ++relatedEpoch;
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
            badge.setBackground(background(palette.badge, 9));
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
        packetList.setDivider(new ColorDrawable(EDGE));
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
                    h.meta = text("", 13, TEXT);
                    h.stamp = text("", 10, MUTED);
                    h.stamp.setSingleLine();
                    h.meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    h.route = text("", landscape() ? 12 : 11, TEXT);
                    h.route.setSingleLine();
                    h.route.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                    h.meta.setSingleLine();
                    if (landscape()) {
                        LinearLayout names = col();
                        names.addView(h.meta);
                        names.addView(h.stamp);
                        h.labels.addView(names, new LinearLayout.LayoutParams(dp(180), -2));
                        h.labels.addView(h.route, new LinearLayout.LayoutParams(0, -2, 1));
                    } else {
                        LinearLayout names = row();
                        names.addView(h.meta, new LinearLayout.LayoutParams(0, -2, 1));
                        names.addView(h.stamp);
                        h.labels.addView(names);
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
                boolean named = alias != null && !alias.isEmpty();
                String direction = r.dir == 1 ? "↑ " : r.dir == 0 ? "↓ " : "? ";
                h.meta.setText(named
                        ? alias
                        : direction + r.protocol() + "  #" + r.id + (alias != null ? " ★" : ""));
                h.meta.setTypeface(null, named ? Typeface.BOLD : Typeface.NORMAL);
                h.meta.setTextColor(named ? TEXT : r.dir == 1 ? palette.outgoing : ACCENT);
                h.stamp.setText(landscape() ? direction + r.protocol() + " · " + r.time()
                                            : r.time() + " · " + PacketStore.formatBytes(r.caplen));
                h.route.setText(r.route());
                h.size.setText(r.caplen + " B  #" + r.id);
                convert.setBackgroundColor(checked ? palette.active : SURFACE);
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
        TextView meta, route, size, stamp;
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
        long[] counts = {total, bookmarks.size(), relatedKey.isEmpty() ? 0 : relatedCount};
        for (int i = 0; i < viewTabs.size(); i++) {
            TextView v = viewTabs.get(i);
            v.setTextColor(i == dataView ? TEXT : MUTED);
            ((View) v.getParent())
                .setBackground(background(i == dataView ? palette.active : 0x00000000, 8));
            TextView badge = viewBadges.get(i);
            badge.setText(counts[i] == -2 ? "!" : counts[i] < 0 ? "…" : "" + counts[i]);
            badge.setVisibility(View.VISIBLE);
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
        if (reset && dataView == 2 && selectedSession != null && !relatedKey.isEmpty()
            && (relatedCount < 0 || PacketStore.count(selectedSession) != knownCount))
            countRelated(selectedSession, relatedKey);
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
    private void countRelated(File session, String key) {
        int token = ++relatedEpoch;
        io.execute(() -> {
            try {
                PacketFilter f = new PacketFilter();
                f.groupKey = key;
                long cursor = -1, total = 0;
                do {
                    PacketStore.Query q = PacketStore.query(session, cursor, f,
                        () -> relatedEpoch != token || Thread.currentThread().isInterrupted());
                    if (q.cancelled)
                        return;
                    total += q.records.size();
                    cursor = q.next;
                } while (cursor >= 0);
                final long count = total;
                post(() -> {
                    if (token == relatedEpoch && session.equals(selectedSession)
                        && key.equals(relatedKey)) {
                        relatedCount = count;
                        updateDataTools();
                    }
                });
            } catch (Exception e) {
                post(() -> {
                    if (token == relatedEpoch) {
                        relatedCount = -2;
                        updateDataTools();
                        toast("关联数量读取失败，可重新打开该连接");
                    }
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
                    if (name != null) {
                        packetAdapter.notifyDataSetChanged();
                        updateDataTools();
                    } else
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
        editTextDialog("收藏名称", bookmarks.getOrDefault(r.id, ""), value -> {
            if (value.length() > 100)
                throw new IOException("最多100字");
            changeNotes(Collections.singleton(r.id), false, value);
        });
    }
    private void searchDialog() {
        LinearLayout box = col();
        box.setPadding(dp(16), dp(16), dp(16), dp(12));
        LinearLayout heading = modalHead(box, "搜索载荷");
        LinearLayout form = modalBody(box);
        gap(form, 6);
        Spinner mode =
            spinner(new String[] {"文本与地址", "十六进制：载荷任意处", "十六进制：载荷开头"},
                packetFilter.magic == null ? 0
                    : packetFilter.prefix  ? 2
                                           : 1);
        form.addView(mode);
        EditText value = input("例如 login 或 01 AB FF");
        String h = "";
        if (packetFilter.magic != null)
            for (byte b : packetFilter.magic) h += String.format(Locale.ROOT, "%02X ", b & 255);
        value.setText(packetFilter.magic == null ? packetFilter.text : h.trim());
        form.addView(value);
        Dialog d = dialog("", box);
        tool(heading, "close", "关闭", () -> d.dismiss());
        LinearLayout footer = row();
        action(footer, "搜索", true, () -> {
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
        action(footer, "清除", false, () -> {
            packetFilter.text = "";
            packetFilter.magic = null;
            d.dismiss();
            loadPackets(true);
        });
        box.addView(footer);
        sizeEditor(d, 260);
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
        LinearLayout heading = modalHead(box, "显示筛选");
        LinearLayout form = modalBody(box);
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
        form.addView(proto);
        form.addView(direction);
        form.addView(readable);
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
        form.addView(sizes);
        Dialog d = dialog("", box);
        tool(heading, "close", "关闭", () -> d.dismiss());
        LinearLayout footer = row();
        action(footer, "应用", true, () -> {
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
        action(footer, "重置", false, () -> {
            packetFilter = new PacketFilter();
            selected.clear();
            d.dismiss();
            loadPackets(true);
        });
        box.addView(footer);
        sizeEditor(d, 340);
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
        String alias = bookmarks.getOrDefault(r.id, "");
        TextView title = text((alias.isEmpty() ? "" : alias + " · ") + "#" + r.id + " · "
                + r.protocol() + " · " + r.time() + " · " + r.caplen + " B",
            13, TEXT);
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
        tool(head, "save", "保存此记录",
            () -> changeNotes(Collections.singleton(r.id), false, null));
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
                    LinearLayout tls = col();
                    boolean[] inspecting = {false};
                    List<Button> tabs = new ArrayList<>();
                    for (String label : r.proto == 6
                            ? new String[] {"原始字节", "可读内容", "网络字段", "TLS 连接"}
                            : new String[] {"原始字节", "可读内容", "网络字段"}) {
                        Button b = compactButton(label, () -> {
                            for (Button t : tabs) {
                                boolean active = label.contentEquals(t.getText());
                                t.setTextColor(active ? ACCENT : MUTED);
                                t.setBackground(background(active ? palette.active : BG, 8));
                            }
                            sc.removeAllViews();
                            if (label.equals("原始字节"))
                                sc.addView(hex);
                            else if (label.equals("TLS 连接")) {
                                sc.addView(tls);
                                if (!inspecting[0]) {
                                    inspecting[0] = true;
                                    inspectTls(r, d, tls);
                                }
                            } else {
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
                        b.setTextColor(tabs.isEmpty() ? ACCENT : MUTED);
                        b.setBackground(background(tabs.isEmpty() ? palette.active : BG, 8));
                        tabs.add(b);
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
            .setItems(new String[] {"通信连接", "协议分布", "包长度分布", "TLS 连接分析"},
                (d, kind) -> {
                    if (kind == 3)
                        tlsConnections();
                    else
                        groupDialog(kind);
                })
            .setNegativeButton("取消", null)
            .show();
    }
    private void tlsConnections() {
        File session = selectedSession;
        LinearLayout box = col();
        box.setPadding(dp(12), dp(4), dp(12), dp(8));
        LinearLayout head = modalHead(box, "TLS 连接分析");
        TextView hint = text("正在汇总 TCP 连接…", 11, MUTED);
        box.addView(hint);
        ListView list = new ListView(this);
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog d = dialog("", box);
        tool(head, "close", "关闭", () -> d.dismiss());
        sizeDialog(d, true);
        io.execute(() -> {
            try {
                List<PacketStore.Group> all = PacketStore.groups(session, 0, () -> !d.isShowing());
                List<PacketStore.Group> flows = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (PacketStore.Group g : all)
                    if (g.key.startsWith("flow:6:")) {
                        flows.add(g);
                        names.add(
                            g.label + "\n" + g.count + " 包 · " + PacketStore.formatBytes(g.bytes));
                    }
                post(() -> {
                    if (!d.isShowing())
                        return;
                    hint.setText(flows.isEmpty() ? "没有可分析的 TCP 连接 · UDP/QUIC 暂不解析"
                                                 : flows.size()
                                + " 个已汇总 TCP 连接 · 点击分析最近一段连接，不凭端口判断 TLS");
                    list.setAdapter(simpleAdapter(names));
                    list.setOnItemClickListener((a, v, n, id) -> {
                        hint.setText("正在定位连接…");
                        list.setEnabled(false);
                        io.execute(() -> {
                            try {
                                PacketFilter filter = new PacketFilter();
                                filter.groupKey = flows.get(n).key;
                                PacketStore.Query q;
                                long cursor = -1;
                                do {
                                    q = PacketStore.query(
                                        session, cursor, filter, () -> !d.isShowing());
                                    cursor = q.next;
                                } while (q.records.isEmpty() && cursor >= 0 && !q.cancelled);
                                if (!q.records.isEmpty()) {
                                    PacketStore.Record record = q.records.get(0);
                                    post(() -> {
                                        if (d.isShowing())
                                            tlsDialog(record);
                                    });
                                } else
                                    post(() -> {
                                        if (d.isShowing())
                                            hint.setText("记录已变化，请重新打开");
                                    });
                            } catch (Exception e) {
                                post(() -> {
                                    if (d.isShowing())
                                        hint.setText("定位失败：" + e.getMessage());
                                });
                            } finally {
                                post(() -> {
                                    if (d.isShowing())
                                        list.setEnabled(true);
                                });
                            }
                        });
                    });
                });
            } catch (Exception e) {
                post(() -> {
                    if (d.isShowing())
                        hint.setText("读取失败：" + e.getMessage());
                });
            }
        });
    }
    private void tlsDialog(PacketStore.Record record) {
        LinearLayout box = col();
        box.setPadding(dp(12), dp(4), dp(12), dp(8));
        LinearLayout head = modalHead(box, "TLS 连接");
        LinearLayout content = modalBody(box);
        Dialog d = dialog("", box);
        tool(head, "close", "关闭", () -> d.dismiss());
        sizeDialog(d, true);
        inspectTls(record, d, content);
    }
    private void tlsField(LinearLayout box, String label, String value) {
        LinearLayout line = col();
        line.setPadding(0, dp(4), 0, dp(5));
        line.addView(text(label, 10, MUTED));
        TextView t = text(value, 13, TEXT);
        t.setTextIsSelectable(true);
        line.addView(t);
        box.addView(line);
    }
    private void inspectTls(PacketStore.Record record, Dialog owner, LinearLayout content) {
        content.removeAllViews();
        content.addView(text("正在重组此连接的连续字节…", 13, ACCENT));
        io.execute(() -> {
            try {
                TlsInspector.Result r =
                    TlsInspector.inspect(record, () -> !owner.isShowing() || disposed);
                post(() -> {
                    if (!owner.isShowing() || r.cancelled)
                        return;
                    content.removeAllViews();
                    LinearLayout hero = card(content, "");
                    LinearLayout title = row();
                    ToolIcon icon = new ToolIcon(this, "tls", "TLS 分析", () -> {});
                    icon.setClickable(false);
                    title.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
                    TextView state = text(r.summary(), 17, ACCENT);
                    state.setTypeface(null, Typeface.BOLD);
                    title.addView(state, new LinearLayout.LayoutParams(0, -2, 1));
                    hero.addView(title);
                    hero.addView(text("被动观察 · 未解密正文", 11, MUTED));
                    gap(hero, 5);
                    hero.addView(text(r.status, 12, TEXT));
                    LinearLayout layout = landscape() ? row() : col();
                    layout.setGravity(Gravity.TOP);
                    LinearLayout left = col(), right = col();
                    if (landscape()) {
                        layout.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
                        layout.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
                    } else {
                        layout.addView(left);
                        layout.addView(right);
                    }
                    content.addView(layout);
                    LinearLayout handshake = card(left, "握手信息");
                    tlsField(handshake, "服务端选择版本", r.version);
                    tlsField(handshake, "可见服务器名称 · SNI", r.sni);
                    tlsField(handshake, "客户端提供的应用协议 · ALPN", r.offeredAlpn);
                    tlsField(handshake, "服务端选择的应用协议", r.selectedAlpn);
                    tlsField(handshake, "加密套件", r.cipher);
                    tlsField(handshake, "客户端提供的 TLS 版本", r.offeredVersions);
                    tlsField(handshake, "ClientHello → ServerHello",
                        r.helloMicros < 0 ? "无法判断"
                                          : String.format(Locale.ROOT,
                                                "%.2f ms · 可见 Hello 间隔，非完整握手耗时",
                                                r.helloMicros / 1000.0));
                    LinearLayout evidence = card(right, "观测范围");
                    tlsField(evidence, "通信端点", r.route);
                    tlsField(evidence, "本次扫描", r.coverage());
                    int rec = 0, encrypted = 0, dup = 0;
                    for (TlsInspector.Direction dir : r.directions) {
                        rec += dir.records;
                        encrypted += dir.encrypted;
                        dup += dir.duplicate;
                    }
                    tlsField(evidence, "可见记录",
                        rec + " 条 · 加密外层 " + encrypted + " 条 · 重复区间 " + dup);
                    if (r.retries > 0)
                        tlsField(evidence, "握手重试请求", r.retries + " 次");
                    List<TlsInspector.Hello> hellos = new ArrayList<>();
                    for (TlsInspector.Direction dir : r.directions) hellos.addAll(dir.hellos);
                    hellos.sort(Comparator.comparingLong(h -> h.time));
                    for (TlsInspector.Hello h : hellos)
                        tlsField(evidence,
                            h.client      ? "ClientHello"
                                : h.retry ? "HelloRetryRequest"
                                          : "ServerHello",
                            new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
                                    .format(new Date(h.time / 1000))
                                + " · 方向 " + (h.side + 1));
                    for (TlsInspector.Direction dir : r.directions)
                        for (String alert : dir.alertLabels)
                            tlsField(evidence, "可见 TLS 告警", alert);
                    LinearLayout cert = card(right, "证书");
                    cert.addView(text(r.certificate, 12, MUTED));
                    if (!r.warnings.isEmpty()) {
                        LinearLayout warnings = card(content, "分析边界");
                        for (String warning : r.warnings) {
                            TextView t = text("• " + warning, 12, AMBER);
                            t.setPadding(0, dp(3), 0, dp(3));
                            warnings.addView(t);
                        }
                    }
                    content.addView(
                        text("未显示的字段表示未观察到；无法据此断定连接失败、安全或不安全。", 11,
                            MUTED));
                });
            } catch (Exception e) {
                post(() -> {
                    if (owner.isShowing()) {
                        content.removeAllViews();
                        content.addView(text("分析失败：" + e.getMessage(), 13, RED));
                    }
                });
            }
        });
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
        if (CaptureService.active && WorkspaceFiles.contains(CaptureService.activeSession, f)) {
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
    private List<File> libraryRoots() {
        Set<File> roots = new LinkedHashSet<>();
        roots.add(new File(getExternalFilesDir(null), "captures"));
        roots.add(baseDir());
        for (String root : prefs.getStringSet("roots", Collections.emptySet()))
            roots.add(new File(root));
        return new ArrayList<>(roots);
    }
    private boolean viewing(File f) {
        return WorkspaceFiles.contains(f, selectedSession);
    }
    private String date(long time) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
            .format(new Date(time));
    }
    private String elapsed(long ms) {
        long seconds = Math.max(0, ms / 1000);
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600,
                                     seconds / 60 % 60, seconds % 60)
                               : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    private WorkspaceFiles browser() throws IOException {
        String configured = baseDir().getCanonicalPath();
        if (browserRoot == null || !configured.equals(browserBase)) {
            browserRoot = baseDir().getCanonicalFile();
            browserDir = browserRoot;
            browserBase = configured;
            fileClipboard.clear();
            historySelection.clear();
            historyManaging = false;
        }
        if (!browserRoot.isDirectory() && !browserRoot.mkdirs())
            throw new IOException("保存目录不可访问，请在设置中选择目录");
        WorkspaceFiles fs = new WorkspaceFiles(browserRoot);
        try {
            fs.path(browserDir, true);
        } catch (IOException e) {
            browserDir = browserRoot;
        }
        return fs;
    }
    private File activeFile() {
        return CaptureService.active ? CaptureService.activeSession : null;
    }
    private void enterDirectory(File dir) {
        try {
            WorkspaceFiles fs = browser();
            fs.path(dir, true);
            browserDir = dir;
            historyManaging = false;
            historySelection.clear();
            showTab(2);
        } catch (Exception e) {
            error("无法进入目录", e);
        }
    }
    private void browserParents() {
        try {
            WorkspaceFiles fs = browser();
            List<File> parents = new ArrayList<>();
            for (File f = browserDir; f != null && f.toPath().startsWith(fs.root);
                f = f.getParentFile()) {
                parents.add(0, f);
                if (f.toPath().equals(fs.root))
                    break;
            }
            String[] names = new String[parents.size()];
            for (int n = 0; n < names.length; n++)
                names[n] = n == 0 ? "保存根目录" : parents.get(n).getName();
            alert()
                .setTitle("转到上层目录")
                .setItems(names, (d, n) -> enterDirectory(parents.get(n)))
                .setNegativeButton("取消", null)
                .show();
        } catch (Exception e) {
            error("路径不可用", e);
        }
    }
    private void history() {
        int epoch = ++historyEpoch;
        LinearLayout page = col();
        body.addView(page);
        LinearLayout head = row();
        TextView title = text(
            historyManaging ? "已选 " + historySelection.size() + " 项" : "文件工作区", 18, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (historyManaging) {
            tool(head, "copy", "复制选中项",
                () -> clipboard(new ArrayList<>(historySelection), false));
            tool(head, "cut", "剪切选中项",
                () -> clipboard(new ArrayList<>(historySelection), true));
            tool(head, "more", "选择与文件操作", () -> browserMenu());
            tool(head, "close", "结束选择", () -> {
                historySelection.clear();
                historyManaging = false;
                showTab(2);
            });
        } else {
            tool(head, "new", "新建文件夹", () -> newDirectory());
            ToolIcon paste = tool(head, "paste", "粘贴到此目录", () -> pasteFiles());
            paste.setEnabled(!fileClipboard.isEmpty());
            tool(head, "more", "目录与文件操作", () -> browserMenu());
        }
        page.addView(head);
        LinearLayout location = row();
        TextView path = text("", 11, MUTED);
        path.setSingleLine();
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        path.setOnClickListener(v -> browserParents());
        location.addView(path, new LinearLayout.LayoutParams(0, dp(28), 1));
        ToolIcon up = tool(location, "up", "上一级", () -> {
            if (browserDir != null && !browserDir.equals(browserRoot))
                enterDirectory(browserDir.getParentFile());
        });
        page.addView(location);
        TextView hint = text(fileClipboard.isEmpty() ? "单击打开 · 长按文件操作"
                                                     : (clipboardMove ? "待移动 " : "待复制 ")
                    + fileClipboard.size() + " 项 · 进入目标目录后粘贴",
            10, ACCENT);
        hint.setPadding(dp(3), 0, 0, dp(4));
        page.addView(hint);
        FrameLayout area = new FrameLayout(this);
        ListView list = new ListView(this);
        // 不拦截列表的点击/滚动，只记录此次手势的实际屏幕坐标。
        final float[] press = {Float.NaN, Float.NaN};
        list.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                press[0] = event.getRawX();
                press[1] = event.getRawY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                press[0] = press[1] = Float.NaN;
            }
            return false;
        });
        list.setDivider(new ColorDrawable(0));
        list.setDividerHeight(dp(5));
        TextView empty = text("正在读取目录…", 13, MUTED);
        empty.setGravity(Gravity.CENTER);
        area.addView(list, new FrameLayout.LayoutParams(-1, -1));
        area.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        page.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        WorkspaceFiles fs;
        File dir;
        try {
            fs = browser();
            dir = browserDir;
            path.setText(dir.getAbsolutePath());
            up.setEnabled(!dir.equals(browserRoot));
            java.nio.file.Path owner = fs.sessionOwner(dir.toPath());
            if (owner != null) {
                hint.setText("会话内部 · 仅浏览/复制");
                ToolIcon menu = tool(location, "more", "会话操作", () -> {});
                menu.setOnClickListener(v -> sessionActions(owner.toFile(), v));
            }
        } catch (Exception e) {
            empty.setText(e.getMessage());
            return;
        }
        io.execute(() -> {
            try {
                List<WorkspaceFiles.Item> entries = fs.list(dir, prefs.getInt("fileSort", 0));
                post(() -> {
                    if (tab != 2 || historyEpoch != epoch)
                        return;
                    fileEntries = entries;
                    empty.setText(entries.isEmpty() ? "此文件夹为空" : "");
                    list.setAdapter(new BaseAdapter() {
                        public int getCount() {
                            return entries.size();
                        }
                        public Object getItem(int p) {
                            return entries.get(p);
                        }
                        public long getItemId(int p) {
                            return p;
                        }
                        public View getView(int n, View reuse, ViewGroup parent) {
                            WorkspaceFiles.Item e = entries.get(n);
                            boolean current = viewing(e.file);
                            LinearLayout line = row();
                            line.setPadding(dp(9), dp(7), dp(7), dp(7));
                            GradientDrawable bg =
                                background(current ? palette.active : SURFACE, 12);
                            if (current)
                                bg.setStroke(dp(1), ACCENT);
                            line.setBackground(bg);
                            if (historyManaging) {
                                CheckBox check = new CheckBox(WorkspaceUi.this);
                                check.setChecked(historySelection.contains(e.file));
                                check.setButtonTintList(
                                    android.content.res.ColorStateList.valueOf(ACCENT));
                                check.setFocusable(false);
                                check.setOnClickListener(v -> {
                                    toggleFile(e.file);
                                    title.setText("已选 " + historySelection.size() + " 项");
                                });
                                line.addView(check, new LinearLayout.LayoutParams(dp(34), dp(40)));
                            } else {
                                if (e.directory) {
                                    ToolIcon icon = new ToolIcon(
                                        WorkspaceUi.this, "folder", "打开文件夹", () -> {});
                                    icon.setClickable(false);
                                    icon.setFocusable(false);
                                    icon.setTooltipText(null);
                                    icon.setLongClickable(false);
                                    line.addView(
                                        icon, new LinearLayout.LayoutParams(dp(30), dp(36)));
                                } else
                                    line.addView(fileIcon(e.file.getName()),
                                        new LinearLayout.LayoutParams(dp(30), dp(36)));
                            }
                            LinearLayout labels = col();
                            LinearLayout first = row();
                            TextView name = text(e.file.getName(), 14, TEXT);
                            name.setTypeface(null, Typeface.BOLD);
                            name.setSingleLine();
                            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                            first.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                            if (current) {
                                TextView tag = text("正在查看", 9, ACCENT);
                                tag.setPadding(dp(5), 0, dp(5), 0);
                                first.addView(tag);
                            }
                            labels.addView(first);
                            TextView meta =
                                text(e.description + " · " + date(e.modified), 10, MUTED);
                            meta.setSingleLine();
                            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            labels.addView(meta);
                            line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

                            return line;
                        }
                    });
                    list.setOnItemClickListener((a, v, n, id) -> {
                        WorkspaceFiles.Item e = entries.get(n);
                        if (historyManaging) {
                            toggleFile(e.file);
                            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
                            title.setText("已选 " + historySelection.size() + " 项");
                        } else if (e.link)
                            toast("符号链接不进入，也不操作");
                        else if (e.directory)
                            enterDirectory(e.file);
                        else
                            openFile(e);
                    });
                    list.setOnItemLongClickListener((a, v, n, id) -> {
                        if (entries.get(n).link)
                            return true;
                        if (historyManaging) {
                            toggleFile(entries.get(n).file);
                            showTab(2);
                        } else
                            fileMenu(entries.get(n), v, press[0], press[1]);
                        return true;
                    });
                });
            } catch (Exception e) {
                post(() -> {
                    if (tab == 2 && historyEpoch == epoch)
                        empty.setText("读取失败：" + e.getMessage());
                });
            }
        });
    }
    private void toggleFile(File file) {
        if (!historySelection.remove(file))
            historySelection.add(file);
    }
    private void browserMenu() {
        List<String> names = new ArrayList<>();
        List<Runnable> run = new ArrayList<>();
        names.add("新建文件夹");
        run.add(() -> newDirectory());
        if (historyManaging) {
            names.add("全选 / 取消全选");
            run.add(() -> {
                int eligible = 0;
                for (WorkspaceFiles.Item e : fileEntries)
                    if (!e.link)
                        eligible++;
                if (historySelection.size() == eligible)
                    historySelection.clear();
                else {
                    historySelection.clear();
                    for (WorkspaceFiles.Item e : fileEntries)
                        if (!e.link)
                            historySelection.add(e.file);
                }
                showTab(2);
            });
            if (historySelection.size() == 1) {
                File f = historySelection.iterator().next();
                names.add("重命名");
                run.add(() -> renameFile(f));
            }
            names.add("删除选中项");
            run.add(() -> confirmDelete(new ArrayList<>(historySelection), null));
        } else {
            names.add("导入 PCAP");
            run.add(()
                        -> pathDialog(
                            Environment.getExternalStorageDirectory(), true, f -> importFile(f)));
            names.add("排序");
            run.add(()
                        -> alert()
                            .setTitle("目录排序 · 文件夹优先")
                            .setSingleChoiceItems(
                                new String[] {"修改时间：最新优先", "修改时间：最早优先",
                                    "名称：A–Z", "文件大小：最大优先"},
                                prefs.getInt("fileSort", 0),
                                (d, n) -> {
                                    prefs.edit().putInt("fileSort", n).apply();
                                    d.dismiss();
                                    showTab(2);
                                })
                            .setNegativeButton("取消", null)
                            .show());
            names.add("选择已登记的保存目录");
            run.add(() -> browserRoots());
            names.add("批量选择");
            run.add(() -> {
                historyManaging = true;
                showTab(2);
            });
            names.add("刷新目录");
            run.add(() -> showTab(2));
        }
        if (!fileClipboard.isEmpty()) {
            names.add("粘贴 " + fileClipboard.size() + " 项");
            run.add(() -> pasteFiles());
            names.add("清空剪贴板");
            run.add(() -> {
                fileClipboard.clear();
                showTab(2);
            });
        }
        alert()
            .setTitle("文件操作")
            .setItems(names.toArray(new String[0]), (d, n) -> run.get(n).run())
            .setNegativeButton("取消", null)
            .show();
    }
    private void browserRoots() {
        List<File> roots = new ArrayList<>();
        for (File f : libraryRoots())
            if (f.isDirectory())
                try {
                    File real = f.getCanonicalFile();
                    if (!roots.contains(real))
                        roots.add(real);
                } catch (IOException ignored) {
                }
        String[] labels = new String[roots.size()];
        for (int n = 0; n < labels.length; n++) labels[n] = roots.get(n).getAbsolutePath();
        alert()
            .setTitle("保存目录")
            .setItems(labels,
                (d, n) -> {
                    browserRoot = roots.get(n);
                    browserDir = browserRoot;
                    fileClipboard.clear();
                    historySelection.clear();
                    historyManaging = false;
                    showTab(2);
                })
            .setNegativeButton("取消", null)
            .show();
    }
    private static final class FileAction {
        final String label, icon;
        final int group;
        final Runnable run;
        FileAction(String label, String icon, int group, Runnable run) {
            this.label = label;
            this.icon = icon;
            this.group = group;
            this.run = run;
        }
    }
    private void openSessionRecords(File file) {
        selectedSession = file;
        historyManaging = false;
        historySelection.clear();
        showTab(1);
    }
    private void sessionActions(File file, View anchor) {
        List<FileAction> actions = new ArrayList<>();
        actions.add(new FileAction("打开记录", "records", 0, () -> openSessionRecords(file)));
        actions.add(new FileAction("导出", "export", 1, () -> exportDialog(file)));
        actions.add(new FileAction("查看报告", "report", 1, () -> reportDialog(file)));
        actions.add(new FileAction("会话概览", "overview", 1, () -> sessionOverview(file)));
        showFileMenu(file.getName(), anchor, Float.NaN, Float.NaN, actions);
    }
    private void fileMenu(WorkspaceFiles.Item e, View anchor, float pressX, float pressY) {
        if (e.link) {
            toast("不操作符号链接");
            return;
        }
        List<FileAction> actions = new ArrayList<>();
        if (e.session)
            actions.add(new FileAction("打开记录", "records", 0,
                () -> openSessionRecords(e.file)));
        else if (!e.directory)
            actions.add(new FileAction("打开", "file", 0, () -> openFile(e)));
        boolean edit = true, copy = true;
        try {
            browser().guard(e.file, activeFile(), false);
        } catch (Exception ex) {
            edit = false;
        }
        try {
            browser().sources(Collections.singletonList(e.file), activeFile(), true);
        } catch (Exception ex) {
            copy = false;
        }
        if (copy)
            actions.add(new FileAction("复制", "copy", 1,
                () -> clipboard(Collections.singletonList(e.file), false)));
        if (edit) {
            actions.add(new FileAction("剪切", "cut", 1,
                () -> clipboard(Collections.singletonList(e.file), true)));
            actions.add(new FileAction("移动到文件夹", "move", 1,
                () -> collectionDialog(Collections.singletonList(e.file))));
            actions.add(new FileAction("重命名", "rename", 1, () -> renameFile(e.file)));
        }
        if (e.session) {
            actions.add(new FileAction("导出", "export", 2, () -> exportDialog(e.file)));
            actions.add(new FileAction("查看报告", "report", 2, () -> reportDialog(e.file)));
            actions.add(new FileAction("会话概览", "overview", 2, () -> sessionOverview(e.file)));
        } else if (!e.directory && e.file.getName().toLowerCase(Locale.ROOT).endsWith(".pcap"))
            actions.add(new FileAction("导入分析", "import", 2, () -> importFile(e.file)));
        actions.add(new FileAction("多选", "select", 3, () -> {
            historyManaging = true;
            historySelection.add(e.file);
            showTab(2);
        }));
        actions.add(new FileAction("属性", "info", 3, () -> fileProperties(e)));
        if (edit)
            actions.add(new FileAction("删除", "trash", 4,
                () -> confirmDelete(Collections.singletonList(e.file), null)));
        showFileMenu(e.file.getName(), anchor, pressX, pressY, actions);
    }
    private void dismissFileMenu() {
        if (fileActionMenu != null) {
            Dialog d = fileActionMenu.dialog;
            restoreFileMenu(d);
            d.dismiss();
        }
    }
    private void restoreFileMenu(Dialog d) {
        if (fileActionMenu != null && fileActionMenu.dialog == d) {
            fileActionMenu.target.setBackground(fileActionMenu.originalBackground);
            fileActionMenu = null;
        }
    }
    private void showFileMenu(String name, View anchor, float pressX, float pressY,
        List<FileAction> actions) {
        dismissFileMenu();
        int[] position = new int[2];
        anchor.getLocationOnScreen(position);
        int x = Float.isNaN(pressX) ? position[0] + anchor.getWidth() / 2 : Math.round(pressX);
        int y = Float.isNaN(pressY) ? position[1] + anchor.getHeight() / 2 : Math.round(pressY);
        LinearLayout box = col();
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        TextView caption = text(name, 11, MUTED);
        caption.setSingleLine();
        caption.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        caption.setPadding(dp(10), dp(7), dp(10), dp(7));
        box.addView(caption);
        Dialog d = track(new Dialog(this));
        d.setCanceledOnTouchOutside(true);
        int lastGroup = -1;
        for (FileAction action : actions) {
            if (lastGroup != -1 && lastGroup != action.group) {
                View divider = new View(this);
                divider.setBackgroundColor(EDGE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
                lp.setMargins(dp(10), dp(4), dp(10), dp(4));
                box.addView(divider, lp);
            }
            lastGroup = action.group;
            boolean danger = action.icon.equals("trash");
            LinearLayout item = row();
            item.setPadding(dp(8), dp(5), dp(10), dp(5));
            item.setMinimumHeight(dp(44));
            item.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(
                ThemePalette.mix(palette.modal, danger ? RED : ACCENT, .20)),
                new ColorDrawable(android.graphics.Color.TRANSPARENT), background(TEXT, 9)));
            ToolIcon icon = new ToolIcon(this, action.icon, action.label, () -> {});
            icon.setClickable(false);
            icon.setFocusable(false);
            icon.setLongClickable(false);
            icon.setTooltipText(null);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (danger)
                icon.setTint(RED);
            item.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
            TextView label = text(action.label, 13, danger ? RED : TEXT);
            label.setPadding(dp(9), 0, 0, 0);
            item.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            item.setFocusable(true);
            item.setContentDescription(action.label);
            item.setOnClickListener(v -> {
                dismissFileMenu();
                action.run.run();
            });
            box.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
        // 内容过高时滚动，窗口尺寸只由紧凑菜单的实测内容与安全区决定。
        ScrollView sc = scroll();
        sc.setFillViewport(false);
        sc.setVerticalScrollBarEnabled(true);
        sc.setScrollbarFadingEnabled(false);
        sc.addView(box);
        d.setContentView(sc);
        fileActionMenu = new FileActionMenu(d, anchor, sc, x, y);
        GradientDrawable highlight = background(palette.active, 12);
        highlight.setStroke(dp(2), ACCENT);
        anchor.setBackground(highlight);
        Window w = d.getWindow();
        w.setBackgroundDrawable(background(palette.modal, 16));
        w.setElevation(dp(16));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0);
        // 外部触摸只关闭菜单，不透传为后面文件的打开操作。
        w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        d.show();
        fitDialog(d);
    }
    private void fitFileMenu(FileActionMenu menu, android.graphics.Rect bounds,
        android.graphics.Insets safe, int ime) {
        ModalGeometry limit = ModalGeometry.fit(bounds.width(), bounds.height(), safe.left,
            safe.top, safe.right, safe.bottom, ime, dp(8), dp(224), dp(480));
        menu.content.measure(View.MeasureSpec.makeMeasureSpec(limit.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(limit.height, View.MeasureSpec.AT_MOST));
        ContextMenuGeometry frame = ContextMenuGeometry.fit(bounds.width(), bounds.height(),
            safe.left, safe.top, safe.right, Math.max(safe.bottom, ime), dp(8), dp(10),
            menu.x - bounds.left, menu.y - bounds.top, limit.width,
            menu.content.getMeasuredHeight());
        Window w = menu.dialog.getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        if (lp.width != frame.width || lp.height != frame.height || lp.x != frame.x
            || lp.y != frame.y) {
            lp.width = frame.width;
            lp.height = frame.height;
            lp.x = frame.x;
            lp.y = frame.y;
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            w.setAttributes(lp);
        }
    }
    private ImageView fileIcon(String name) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(name.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? R.drawable.ic_archive
                : R.drawable.ic_document);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        icon.setPadding(dp(2), 0, dp(2), 0);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setContentDescription("文件");
        return icon;
    }
    private void openFile(WorkspaceFiles.Item entry) {
        String name = entry.file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            archiveDialog(entry.file);
            return;
        }
        if (name.endsWith(".pcap")) {
            File parent = entry.file.getParentFile();
            if (name.matches("capture_\\d+\\.pcap") && new File(parent, "packets.idx").isFile()
                && new File(parent, "session.json").isFile()) {
                selectedSession = parent;
                historyManaging = false;
                historySelection.clear();
                showTab(1);
                toast("已打开所属抓包会话");
            } else
                importFile(entry.file);
            return;
        }
        previewFile(entry.file.getName(), entry.file.length(), () -> {
            try (InputStream in = new FileInputStream(entry.file)) {
                return readPreview(in);
            }
        });
    }
    private byte[] readPreview(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        while (out.size() < 65536) {
            int n = in.read(b, 0, Math.min(b.length, 65536 - out.size()));
            if (n < 0)
                break;
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
    private interface PreviewBytes {
        byte[] read() throws Exception;
    }
    private void previewFile(String title, long size, PreviewBytes loader) {
        LinearLayout box = col();
        box.setPadding(dp(12), dp(4), dp(12), dp(8));
        LinearLayout head = modalHead(box, title);
        TextView hint = text("只读预览 · " + (size < 0 ? "大小未知" : PacketStore.formatBytes(size))
                + " · 最多64KiB",
            10, MUTED);
        box.addView(hint);
        LinearLayout content = modalBody(box);
        TextView loading = text("读取中…", 13, MUTED);
        content.addView(loading);
        Dialog d = dialog("", box);
        tool(head, "close", "关闭", () -> d.dismiss());
        sizeDialog(d, true);
        io.execute(() -> {
            try {
                byte[] raw = loader.read();
                boolean readable = PacketFilter.isReadable(raw) || raw.length == 0;
                post(() -> {
                    if (!d.isShowing())
                        return;
                    content.removeAllViews();
                    if (readable) {
                        TextView text = text(new String(raw, StandardCharsets.UTF_8), 12, TEXT);
                        text.setTypeface(Typeface.MONOSPACE);
                        text.setTextIsSelectable(true);
                        content.addView(text);
                    } else {
                        HexDumpView hex = new HexDumpView(this);
                        hex.setBytes(raw);
                        content.addView(hex);
                        hint.setText(hint.getText() + " · 原始字节");
                    }
                });
            } catch (Exception e) {
                post(() -> {
                    if (d.isShowing())
                        loading.setText("无法读取：" + e.getMessage());
                });
            }
        });
    }
    private void archiveDialog(File file) {
        LinearLayout box = col();
        box.setPadding(dp(12), dp(4), dp(12), dp(8));
        LinearLayout head = modalHead(box, file.getName());
        LinearLayout nav = row();
        TextView path = text("/", 11, MUTED);
        path.setSingleLine();
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        nav.addView(path, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(nav);
        ListView list = new ListView(this);
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView status = text("只读浏览 · 不解压，不改原文件", 10, MUTED);
        box.addView(status);
        Dialog d = dialog("", box);
        tool(head, "close", "关闭压缩包", () -> d.dismiss());
        String[] current = {""};
        ArchiveIndex[] archive = {null};
        int[] epoch = {0};
        Runnable[] load = {null};
        tool(nav, "up", "上一级", () -> {
            String prefix = current[0];
            if (prefix.isEmpty())
                return;
            String trim = prefix.substring(0, prefix.length() - 1);
            int slash = trim.lastIndexOf('/');
            current[0] = slash < 0 ? "" : trim.substring(0, slash + 1);
            load[0].run();
        });
        load[0] = () -> {
            int token = ++epoch[0];
            String prefix = current[0];
            path.setText("/" + prefix);
            status.setText("读取目录…");
            io.execute(() -> {
                try {
                    if (archive[0] == null)
                        archive[0] = new ArchiveIndex(file);
                    List<ArchiveIndex.Entry> entries = archive[0].list(prefix);
                    post(() -> {
                        if (!d.isShowing() || epoch[0] != token)
                            return;
                        status.setText(entries.size() + " 项 · 只读浏览，不解压");
                        list.setAdapter(new BaseAdapter() {
                            public int getCount() {
                                return entries.size();
                            }
                            public Object getItem(int n) {
                                return entries.get(n);
                            }
                            public long getItemId(int n) {
                                return n;
                            }
                            public View getView(int n, View reuse, ViewGroup parent) {
                                ArchiveIndex.Entry e = entries.get(n);
                                LinearLayout line = row();
                                line.setPadding(dp(6), dp(6), dp(6), dp(6));
                                if (e.directory) {
                                    ToolIcon icon = new ToolIcon(
                                        WorkspaceUi.this, "folder", "文件夹", () -> {});
                                    icon.setClickable(false);
                                    icon.setFocusable(false);
                                    icon.setTooltipText(null);
                                    icon.setLongClickable(false);
                                    line.addView(
                                        icon, new LinearLayout.LayoutParams(dp(30), dp(36)));
                                } else
                                    line.addView(fileIcon(e.name),
                                        new LinearLayout.LayoutParams(dp(30), dp(36)));
                                TextView title = text(e.name
                                        + (e.directory ? ""
                                                       : "\n"
                                                    + (e.size < 0
                                                            ? "大小未知"
                                                            : PacketStore.formatBytes(e.size))),
                                    13, TEXT);
                                line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                                return line;
                            }
                        });
                        list.setOnItemClickListener((a, v, n, id) -> {
                            ArchiveIndex.Entry e = entries.get(n);
                            if (e.directory) {
                                current[0] = e.path;
                                load[0].run();
                            } else
                                previewFile(e.name, e.size, () -> archive[0].preview(e.path));
                        });
                    });
                } catch (Exception e) {
                    post(() -> {
                        if (d.isShowing() && epoch[0] == token) {
                            list.setAdapter(null);
                            status.setText("无法打开：" + e.getMessage());
                        }
                    });
                }
            });
        };
        dialogBackActions.put(d, () -> {
            String prefix = current[0];
            if (prefix.isEmpty()) {
                d.dismiss();
                return;
            }
            String trim = prefix.substring(0, prefix.length() - 1);
            int slash = trim.lastIndexOf('/');
            current[0] = slash < 0 ? "" : trim.substring(0, slash + 1);
            load[0].run();
        });
        sizeDialog(d, true);
        load[0].run();
    }
    private void fileProperties(WorkspaceFiles.Item e) {
        LinearLayout box = col();
        box.setPadding(dp(12), dp(4), dp(12), dp(8));
        LinearLayout head = modalHead(box, "文件信息");
        LinearLayout content = modalBody(box);
        information(content, "名称", e.file.getName());
        information(content, "路径", e.file.getAbsolutePath());
        information(content, "修改时间", date(e.modified));
        information(content, "类型",
            e.session         ? "抓包会话目录"
                : e.directory ? "文件夹"
                              : "文件 · " + PacketStore.formatBytes(e.size));
        Dialog d = dialog("", box);
        tool(head, "close", "关闭", () -> d.dismiss());
        sizeEditor(d, 420);
    }
    private void newDirectory() {
        try {
            WorkspaceFiles fs = browser();
            File parent = browserDir;
            editTextDialog("新建文件夹", "", name -> {
                fs.mkdir(parent, name, activeFile());
                showTab(2);
            });
        } catch (Exception e) {
            error("无法新建文件夹", e);
        }
    }
    private void renameFile(File file) {
        try {
            WorkspaceFiles fs = browser();
            fs.guard(file, activeFile(), false);
            editTextDialog("重命名实际文件 / 文件夹", file.getName(), name -> {
                File renamed = fs.rename(file, name, activeFile());
                WorkspaceFiles.Result r = new WorkspaceFiles.Result();
                r.moved.put(file, renamed);
                fileClipboard.clear();
                applyFileResult(r);
            });
        } catch (Exception e) {
            error("无法重命名", e);
        }
    }
    private void clipboard(List<File> files, boolean move) {
        try {
            WorkspaceFiles fs = browser();
            fs.sources(files, activeFile(), !move);
            fileClipboard.clear();
            fileClipboard.addAll(files);
            clipboardMove = move;
            historySelection.clear();
            historyManaging = false;
            showTab(2);
            toast("已" + (move ? "剪切" : "复制") + files.size() + " 项，进入目标文件夹后点粘贴");
        } catch (Exception e) {
            error("无法选择这些文件", e);
        }
    }
    private void pasteFiles() {
        if (fileClipboard.isEmpty()) {
            toast("剪贴板为空");
            return;
        }
        try {
            WorkspaceFiles fs = browser();
            List<File> files = new ArrayList<>(fileClipboard);
            File destination = browserDir;
            boolean moving = clipboardMove;
            runFileOperation(moving ? "移动文件" : "复制文件",
                (cancel, progress)
                    -> fs.transfer(files, destination, moving, activeFile(), cancel, progress),
                result -> {
                    if (moving) {
                        for (File f : result.moved.keySet()) fileClipboard.remove(f);
                    }
                    applyFileResult(result);
                });
        } catch (Exception e) {
            error("无法粘贴", e);
        }
    }
    private interface FileJob {
        WorkspaceFiles.Result run(WorkspaceFiles.Cancel cancel, WorkspaceFiles.Progress progress)
            throws Exception;
    }
    private void runFileOperation(
        String title, FileJob job, java.util.function.Consumer<WorkspaceFiles.Result> done) {
        ProgressDialog wait = track(new ProgressDialog(this));
        wait.setTitle(title);
        wait.setMessage("准备文件操作…");
        wait.setIndeterminate(true);
        wait.setCancelable(false);
        fixedDialogs.add(wait);
        java.util.concurrent.atomic.AtomicBoolean cancel =
            new java.util.concurrent.atomic.AtomicBoolean();
        wait.setButton(DialogInterface.BUTTON_NEGATIVE, "取消", (d, n) -> {});
        wait.show();
        wait.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
            cancel.set(true);
            wait.setMessage("正在取消；已完成的操作会保留…");
        });
        io.execute(() -> {
            try {
                long[] last = {0};
                WorkspaceFiles.Result result = job.run(cancel::get, value -> {
                    long now = System.currentTimeMillis();
                    if (now - last[0] > 120) {
                        last[0] = now;
                        post(() -> {
                            if (wait.isShowing() && !cancel.get())
                                wait.setMessage(value);
                        });
                    }
                });
                post(() -> {
                    wait.dismiss();
                    done.accept(result);
                });
            } catch (Exception e) {
                post(() -> {
                    wait.dismiss();
                    if (tab == 2)
                        showTab(2);
                    error("文件操作未全部完成", e);
                });
            }
        });
    }
    private void forgetFile(File file) {
        if (viewing(file))
            selectedSession = null;
        if (!CaptureService.active && WorkspaceFiles.contains(file, CaptureService.activeSession))
            synchronized (CaptureService.LOCK) {
                CaptureService.activeSession = null;
                CaptureService.live = new JSONObject();
            }
    }
    private void applyFileResult(WorkspaceFiles.Result result) {
        for (Map.Entry<File, File> change : result.moved.entrySet()) {
            selectedSession =
                WorkspaceFiles.remap(selectedSession, change.getKey(), change.getValue());
            if (!CaptureService.active)
                CaptureService.activeSession = WorkspaceFiles.remap(
                    CaptureService.activeSession, change.getKey(), change.getValue());
            browserDir = WorkspaceFiles.remap(browserDir, change.getKey(), change.getValue());
            for (int i = 0; i < fileClipboard.size(); i++)
                fileClipboard.set(i,
                    WorkspaceFiles.remap(fileClipboard.get(i), change.getKey(), change.getValue()));
        }
        for (File file : result.deleted) forgetFile(file);
        dataSession = null;
        generation++;
        relatedEpoch++;
        queryBusy = false;
        records.clear();
        loadedIds.clear();
        bookmarks.clear();
        selected.clear();
        knownCount = -1;
        historySelection.clear();
        historyManaging = false;
        if (tab == 2)
            showTab(2);
        if (!result.failures.isEmpty())
            error("部分项目未完成", new IOException(String.join("\n", result.failures)));
        else
            toast("文件操作完成");
    }
    private void metric(LinearLayout row, String value, String label) {
        LinearLayout tile = col();
        tile.setPadding(dp(10), dp(8), dp(10), dp(8));
        tile.setBackground(background(BG, 10));
        TextView number = text(value, 20, TEXT);
        number.setSingleLine();
        number.setEllipsize(android.text.TextUtils.TruncateAt.END);
        number.setTypeface(null, Typeface.BOLD);
        tile.addView(number);
        tile.addView(text(label, 10, MUTED));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(tile, lp);
    }
    private void information(LinearLayout parent, String label, String value) {
        LinearLayout line = row();
        line.setGravity(Gravity.TOP);
        line.setPadding(dp(3), dp(5), dp(3), dp(5));
        TextView key = text(label, 11, MUTED);
        line.addView(key, new LinearLayout.LayoutParams(dp(72), -2));
        TextView text = text(value, 12, TEXT);
        text.setTextIsSelectable(true);
        line.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(line);
    }
    private void sessionOverview(File file) {
        LinearLayout box = col();
        box.setPadding(dp(12), dp(4), dp(12), dp(8));
        LinearLayout head = modalHead(box, "会话概览");
        LinearLayout content = modalBody(box);
        TextView loading = text("读取中…", 13, MUTED);
        content.addView(loading);
        Dialog d = dialog("", box);
        tool(head, "folder", "浏览会话文件", () -> {
            d.dismiss();
            enterDirectory(file);
        });
        tool(head, "close", "关闭概览", () -> d.dismiss());
        sizeEditor(d, 440);
        io.execute(() -> {
            try {
                SessionLibrary.Entry e = SessionLibrary.read(file);
                post(() -> {
                    if (!d.isShowing())
                        return;
                    content.removeAllViews();
                    ((TextView) head.getChildAt(0)).setText(e.title);
                    JSONObject m = e.meta;
                    TextView caption =
                        text((viewing(file) ? "正在查看 · " : "") + date(e.started), 11, ACCENT);
                    caption.setPadding(dp(3), 0, dp(3), dp(6));
                    content.addView(caption);
                    LinearLayout first = row(), second = row();
                    content.addView(first);
                    if (!landscape())
                        content.addView(second);
                    else
                        second = first;
                    metric(first, "" + e.packets, "主会话包数");
                    metric(first, PacketStore.formatBytes(e.bytes), "本地文件占用");
                    metric(second, e.duration > 0 ? elapsed(e.duration) : "—", "采集时长");
                    metric(second, "" + e.unknown, "未知归属保留");
                    gap(content, 8);
                    LinearLayout quality = card(content, "质量摘要");
                    information(quality, "内核 / 队列",
                        (m.optBoolean("kernelStatsAvailable") ? "" + m.optLong("kernelDrops")
                                                              : "未采样")
                            + " / " + m.optLong("queueDrops"));
                    information(quality, "未知排除", "" + m.optLong("unresolved"));
                    information(quality, "截断 / 解析",
                        m.optLong("truncated") + " / " + m.optLong("parseFailedSaved"));
                    if (!m.optString("error").isEmpty())
                        quality.addView(text(m.optString("error"), 12, RED));
                    information(content, "抓取目标",
                        e.target + (m.optInt("uid", -1) >= 0 ? " · UID " + m.optInt("uid") : ""));
                    information(content, "所在文件夹", file.getParentFile().getName());
                    if (m.has("finished"))
                        information(content, "结束时间", date(m.optLong("finished")));
                    if (!e.description.isEmpty())
                        information(content, "操作备注", e.description);
                    information(content, "本地目录", file.getAbsolutePath());
                });
            } catch (Exception e) {
                post(() -> loading.setText("读取失败：" + e.getMessage()));
            }
        });
    }
    private void collectionDialog(List<File> files) {
        collectionDialog(files, null);
    }
    private void collectionDialog(List<File> files, Dialog overview) {
        try {
            WorkspaceFiles fs = browser();
            fs.sources(files, activeFile());
            folderDialog(browserRoot, destination -> {
                // A picker can browse externally; the filesystem workspace enforces this root.
                if (overview != null)
                    overview.dismiss();
                runFileOperation("移动到文件夹",
                    (cancel, progress)
                        -> fs.transfer(files, destination, true, activeFile(), cancel, progress),
                    result -> applyFileResult(result));
            });
        } catch (Exception e) {
            error("无法移动", e);
        }
    }
    private void confirmDelete(List<File> files, Dialog overview) {
        if (files.isEmpty()) {
            toast("请先选择文件或文件夹");
            return;
        }
        alert()
            .setTitle("删除 " + files.size() + " 个本地项目？")
            .setMessage("会实际删除手机中的文件。文件夹包含的子文件夹、原包、索引、收藏和报告也将删"
                + "除，无法撤销。正在采集的会话及其父目录受保护。")
            .setNegativeButton("保留", null)
            .setPositiveButton("彻底删除",
                (d, n) -> {
                    try {
                        WorkspaceFiles fs = browser();
                        fs.sources(files, activeFile());
                        if (overview != null)
                            overview.dismiss();
                        fileClipboard.clear();
                        runFileOperation("删除文件",
                            (cancel, progress)
                                -> fs.delete(files, activeFile(), cancel, progress),
                            result -> {
                                if (!result.failures.isEmpty())
                                    for (File file : files) forgetFile(file);
                                applyFileResult(result);
                            });
                    } catch (Exception e) {
                        error("无法删除", e);
                    }
                })
            .show();
    }
    private void exportDialog(File session) {
        if (CaptureService.active
            && WorkspaceFiles.contains(CaptureService.activeSession, session)) {
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
        if (activeFile() != null && WorkspaceFiles.contains(activeFile(), file)) {
            toast("请先停止采集，再导入正在写入的文件");
            return;
        }
        ProgressDialog wait = progress("导入中", "正在检查 PCAP 并建立索引…", false);
        io.execute(() -> {
            File dir = null;
            try (InputStream in = new FileInputStream(file)) {
                File base = browserDir != null && tab == 2 ? browserDir : baseDir();
                WorkspaceFiles fs = browser();
                if (fs.sessionOwner(base.toPath()) != null)
                    base = browserRoot;
                fs.guard(base, activeFile(), true);
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
    private LinearLayout preferencesCard(LinearLayout parent, String number, String title) {
        LinearLayout card = card(parent, "");
        LinearLayout head = row();
        TextView index = text(number, 11, ACCENT);
        index.setGravity(Gravity.CENTER);
        index.setBackground(background(palette.active, 7));
        head.addView(index, new LinearLayout.LayoutParams(dp(28), dp(26)));
        TextView label = text(title, 16, TEXT);
        label.setPadding(dp(9), 0, 0, 0);
        label.setTypeface(null, Typeface.BOLD);
        head.addView(label);
        card.addView(head);
        gap(card, 6);
        return card;
    }
    private void preference(
        LinearLayout card, String title, String value, String icon, Runnable run) {
        LinearLayout line = row();
        line.setMinimumHeight(dp(48));
        ToolIcon symbol = new ToolIcon(this, icon, title, run);
        line.addView(symbol, new LinearLayout.LayoutParams(dp(34), dp(40)));
        TextView key = text(title, 13, TEXT);
        line.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        TextView current = text(value, 12, ACCENT);
        if (title.equals("背景不透明度"))
            current.setTag("opacity-value");
        current.setGravity(Gravity.RIGHT);
        current.setSingleLine();
        current.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        line.addView(current, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text(" ›", 18, MUTED);
        line.addView(arrow);
        line.setOnClickListener(v -> run.run());
        card.addView(line);
    }
    private void preferenceToggle(
        LinearLayout card, String title, String key, boolean initial, String icon) {
        LinearLayout line = row();
        line.setMinimumHeight(dp(48));
        ToolIcon image = new ToolIcon(this, icon, title, () -> {});
        image.setClickable(false);
        line.addView(image, new LinearLayout.LayoutParams(dp(34), dp(40)));
        TextView label = text(title, 13, TEXT);
        line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        toggle.setContentDescription(title);
        toggle.setChecked(prefs.getBoolean(key, initial));
        toggle.setThumbTintList(new android.content.res.ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
            new int[] {ACCENT, palette.disabled}));
        toggle.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            toast("已更新，下次抓包生效");
        });
        line.addView(toggle);
        line.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        card.addView(line);
    }
    private void settings() {
        ScrollView sc = scroll();
        LinearLayout page = col();
        sc.addView(page);
        body.addView(sc);
        LinearLayout heading = row();
        TextView title = text("偏好设置", 23, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = text("NetCap · 0.4.3", 11, ACCENT);
        version.setPadding(dp(9), dp(5), dp(9), dp(5));
        version.setBackground(background(palette.active, 9));
        heading.addView(version);
        page.addView(heading);
        gap(page, 12);
        LinearLayout layout = landscape() ? row() : col();
        layout.setGravity(Gravity.TOP);
        LinearLayout left = col(), right = col();
        if (landscape()) {
            layout.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
            layout.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        } else {
            layout.addView(left);
            layout.addView(right);
        }
        page.addView(layout);
        LinearLayout storage = preferencesCard(left, "01", "存储与命名");
        preference(storage, "保存目录", baseDir().getName(), "folder",
            () -> folderDialog(baseDir(), folder -> {
                Set<String> roots =
                    new HashSet<>(prefs.getStringSet("roots", Collections.emptySet()));
                roots.add(folder.getAbsolutePath());
                prefs.edit()
                    .putString("directory", folder.getAbsolutePath())
                    .putStringSet("roots", roots)
                    .apply();
                showTab(3);
            }));
        TextView path = text(baseDir().getAbsolutePath(), 11, MUTED);
        path.setMaxLines(2);
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        path.setPadding(dp(5), 0, dp(5), dp(5));
        storage.addView(path);
        preference(storage, "文件名前缀", prefs.getString("prefix", "抓包"), "save",
            () -> editTextDialog("文件名前缀", prefs.getString("prefix", "抓包"), name -> {
                SessionLibrary.validLeaf(name, 40);
                prefs.edit().putString("prefix", name).apply();
                showTab(3);
            }));
        LinearLayout capture = preferencesCard(left, "02", "采集策略");
        preference(capture, "抓取范围", prefs.getInt("uid", -1) < 0 ? "整机 IP" : "选中应用",
            "select", () -> {
                if (CaptureService.active) {
                    toast("请先停止后切换目标");
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
        preference(capture, "保存协议",
            prefs.getInt("protocol", 0) == 6        ? "TCP"
                : prefs.getInt("protocol", 0) == 17 ? "UDP"
                                                    : "全部 IP",
            "filter",
            ()
                -> alert()
                    .setTitle("保存协议")
                    .setItems(new String[] {"全部 IP 协议", "仅 TCP", "仅 UDP"},
                        (d, n) -> {
                            prefs.edit().putInt("protocol", n == 1 ? 6 : n == 2 ? 17 : 0).apply();
                            showTab(3);
                        })
                    .setNegativeButton("取消", null)
                    .show());
        preference(capture, "采集接口", prefs.getString("ifaceName", "所有接口"), "group",
            () -> interfaces());
        preferenceToggle(capture, "独立保留未知归属包", "keepUnknown", false, "save");
        preferenceToggle(capture, "熄屏保持抓包", "wake", true, "select");
        LinearLayout appearance = preferencesCard(right, "03", "外观与交互");
        preference(appearance, "全局配色", palette.name, "palette", () -> themeDialog());
        preference(appearance, "背景不透明度", prefs.getInt("opacity", 86) + "%", "select",
            () -> opacityDialog());
        preference(appearance, "收藏与关联", "前往数据页", "save", () -> showTab(1));
        LinearLayout tools = preferencesCard(right, "04", "诊断与维护");
        preference(tools, "检查 Root 核心", "运行检查", "refresh", () -> probe());
        preference(tools, "导出设备诊断", "导出", "import", () -> diagnostic());
        preference(tools, "管理本地会话", "会话库", "folder", () -> showTab(2));
        preference(tools, "结束工具", "退出", "close", () -> {
            if (CaptureService.active)
                alert()
                    .setTitle("停止并退出？")
                    .setMessage("将先排空并保存抓包文件，再结束工具。")
                    .setPositiveButton("保存并退出", (d, w) -> service.exitTool())
                    .setNegativeButton("取消", null)
                    .show();
            else
                service.exitTool();
        });
    }
    private void themeDialog() {
        LinearLayout box = col();
        box.setPadding(dp(10), dp(4), dp(10), dp(8));
        LinearLayout head = modalHead(box, "主题画廊");
        LinearLayout content = modalBody(box);
        content.addView(text(ThemePalette.ALL.length + " 套配色 · 先预览，再应用", 11, MUTED));
        LinearLayout gallery = col();
        content.addView(gallery);
        ThemeGrid grid = new ThemeGrid(this);
        gallery.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout preview = col();
        gallery.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        ThemePalette[] choice = {palette};
        List<LinearLayout> cards = new ArrayList<>();
        List<TextView> checks = new ArrayList<>();
        Runnable render = () -> {
            for (int i = 0; i < cards.size(); i++) {
                ThemePalette t = ThemePalette.ALL[i];
                boolean on = t == choice[0];
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(t.workspace);
                bg.setCornerRadius(dp(12));
                bg.setStroke(dp(on ? 2 : 1), on ? t.accent : t.edge);
                cards.get(i).setBackground(bg);
                cards.get(i).setSelected(on);
                checks.get(i).setText(on ? "✓" : "");
                cards.get(i).setContentDescription(
                    t.name + "，" + t.mood + (on ? "，已选预览" : ""));
            }
            ThemePalette t = choice[0];
            preview.removeAllViews();
            preview.setPadding(dp(12), dp(12), dp(12), dp(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(t.workspace);
            bg.setCornerRadius(dp(12));
            bg.setStroke(dp(1), t.edge);
            preview.setBackground(bg);
            TextView heading = text(t.name, 17, t.text);
            heading.setTypeface(null, Typeface.BOLD);
            preview.addView(heading);
            preview.addView(text(t.mood, 11, t.muted));
            gap(preview, 10);
            TextView example = text("↑ TCP     128 B\n192.0.2.1 → 198.51.100.2", 11, t.accent);
            example.setPadding(dp(10), dp(10), dp(10), dp(10));
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(t.surface);
            panel.setCornerRadius(dp(8));
            example.setBackground(panel);
            preview.addView(example);
            TextView action = text("开始观察", 12, t.onAccent);
            action.setGravity(Gravity.CENTER);
            action.setPadding(0, dp(7), 0, dp(7));
            GradientDrawable button = new GradientDrawable();
            button.setColor(t.accent);
            button.setCornerRadius(dp(8));
            action.setBackground(button);
            LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(-1, -2);
            buttonLp.topMargin = dp(8);
            preview.addView(action, buttonLp);
            TextView caption = text("预览 · 界面、弹窗与悬浮球统一配色", 10, t.muted);
            caption.setPadding(0, dp(8), 0, 0);
            preview.addView(caption);
        };
        for (ThemePalette t : ThemePalette.ALL) {
            LinearLayout card = col();
            card.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout top = row();
            TextView title = text(t.name, 13, t.text);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine();
            top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            TextView check = text("", 14, t.accent);
            check.setGravity(Gravity.CENTER);
            top.addView(check, new LinearLayout.LayoutParams(dp(20), dp(22)));
            card.addView(top);
            TextView mini = text("↑ TCP   128 B", 10, t.accent);
            mini.setPadding(dp(6), dp(6), dp(6), dp(6));
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(t.surface);
            panel.setCornerRadius(dp(6));
            mini.setBackground(panel);
            LinearLayout.LayoutParams miniLp = new LinearLayout.LayoutParams(-1, -2);
            miniLp.topMargin = dp(4);
            card.addView(mini, miniLp);
            TextView mood = text(t.mood, 10, t.muted);
            mood.setSingleLine();
            mood.setPadding(0, dp(4), 0, 0);
            card.addView(mood);
            card.setFocusable(true);
            card.setOnClickListener(v -> {
                choice[0] = t;
                render.run();
            });
            cards.add(card);
            checks.add(check);
        }
        for (LinearLayout card : cards) grid.addView(card);
        boolean[] horizontal = {false};
        gallery.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            boolean wide = r - l >= dp(640);
            if (wide == horizontal[0])
                return;
            horizontal[0] = wide;
            gallery.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            gallery.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams gl =
                new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 2 : 0);
            LinearLayout.LayoutParams pl =
                new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1 : 0);
            pl.leftMargin = wide ? dp(8) : 0;
            pl.topMargin = wide ? dp(4) : dp(8);
            grid.setLayoutParams(gl);
            preview.setLayoutParams(pl);
        });
        render.run();
        Dialog d = dialog("", box);
        tool(head, "close", "取消配色", () -> d.dismiss());
        LinearLayout footer = row();
        action(footer, "取消", false, () -> d.dismiss());
        action(footer, "应用配色", true, () -> {
            prefs.edit().putString("theme", choice[0].id).apply();
            for (Dialog modal : new ArrayList<>(openDialogs)) modal.dismiss();
            loadPalette();
            build();
            toast("已应用 " + palette.name);
        });
        box.addView(footer);
        sizeDialog(d, true);
    }
    private void opacityDialog() {
        LinearLayout box = col();
        box.setPadding(dp(14), dp(4), dp(14), dp(8));
        LinearLayout head = modalHead(box, "背景不透明度");
        LinearLayout content = modalBody(box);
        TextView value = text(prefs.getInt("opacity", 86) + "%", 28, ACCENT);
        value.setGravity(Gravity.CENTER);
        content.addView(value);
        SeekBar slider = new SeekBar(this);
        slider.setMax(50);
        slider.setProgress(prefs.getInt("opacity", 86) - 45);
        content.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user) {
                    prefs.edit().putInt("opacity", progress + 45).apply();
                    value.setText((progress + 45) + "%");
                    applyBackdrop();
                    View current = body.findViewWithTag("opacity-value");
                    if (current instanceof TextView)
                        ((TextView) current).setText((progress + 45) + "%");
                }
            }
        });
        Dialog d = dialog("", box);
        tool(head, "close", "关闭", () -> {
            d.dismiss();
            showTab(3);
        });
        LinearLayout footer = row();
        action(footer, "完成", true, () -> {
            d.dismiss();
            showTab(3);
        });
        box.addView(footer);
        sizeEditor(d, 240);
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
        box.setPadding(dp(12), dp(4), dp(12), dp(8));
        LinearLayout head = modalHead(box, files ? "选择 PCAP 文件" : "选择保存目录");
        TextView path = text("", 12, ACCENT);
        path.setSingleLine();
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        box.addView(path, new LinearLayout.LayoutParams(-1, dp(24)));
        LinearLayout toolbar = row();
        box.addView(toolbar);
        FrameLayout area = new FrameLayout(this);
        ListView list = new ListView(this);
        TextView empty = text("读取中…", 13, MUTED);
        empty.setGravity(Gravity.CENTER);
        area.addView(list, new FrameLayout.LayoutParams(-1, -1));
        area.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        box.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        Dialog d = dialog("", box);
        tool(head, "close", "关闭文件选择", () -> d.dismiss());
        File[] current = {start};
        List<File> entries = new ArrayList<>();
        boolean[] byTime = {false};
        int[] scanToken = {0};
        Runnable[] scan = {null};
        scan[0] = () -> {
            File folder = current[0];
            final boolean orderByTime = byTime[0];
            int token = ++scanToken[0];
            path.setText(folder.getAbsolutePath());
            empty.setText("读取中…");
            io.execute(() -> {
                File[] children = folder.listFiles();
                List<File> found = new ArrayList<>();
                if (children != null)
                    for (File f : children)
                        if (f.isDirectory() || files && f.isFile())
                            found.add(f);
                found.sort((x, y)
                               -> x.isDirectory() != y.isDirectory() ? (x.isDirectory() ? -1 : 1)
                        : orderByTime && x.lastModified() != y.lastModified()
                        ? Long.compare(y.lastModified(), x.lastModified())
                        : x.getName().compareToIgnoreCase(y.getName()));
                post(() -> {
                    if (!d.isShowing() || token != scanToken[0])
                        return;
                    entries.clear();
                    entries.addAll(found);
                    list.setAdapter(new BaseAdapter() {
                        public int getCount() {
                            return entries.size();
                        }
                        public Object getItem(int i) {
                            return entries.get(i);
                        }
                        public long getItemId(int i) {
                            return i;
                        }
                        public View getView(int i, View cv, ViewGroup parent) {
                            File f = entries.get(i);
                            LinearLayout line = row();
                            line.setPadding(dp(6), dp(5), dp(6), dp(5));
                            TextView symbol = text(f.isDirectory() ? "▸" : "▤", 18, ACCENT);
                            line.addView(symbol, new LinearLayout.LayoutParams(dp(28), dp(32)));
                            LinearLayout labels = col();
                            TextView name = text(f.getName(), 14, TEXT);
                            name.setSingleLine();
                            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                            labels.addView(name);
                            labels.addView(
                                text(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
                                            .format(new Date(f.lastModified()))
                                        + (f.isDirectory()
                                                ? ""
                                                : " · " + PacketStore.formatBytes(f.length())),
                                    10, MUTED));
                            line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                            return line;
                        }
                    });
                    empty.setText(
                        children == null ? "无法读取此目录 · 点击查看位置与权限" : "此目录为空");
                });
            });
        };
        Runnable locations = ()
            -> alert()
                   .setTitle("位置与访问")
                   .setItems(new String[] {"内部存储", "应用文件目录", "输入目录路径",
                                 "授予共享存储访问"},
                       (x, n) -> {
                           if (n < 2) {
                               current[0] = n == 0 ? Environment.getExternalStorageDirectory()
                                                   : getExternalFilesDir(null);
                               scan[0].run();
                           } else if (n == 2)
                               editTextDialog("转到目录", current[0].getAbsolutePath(), value -> {
                                   File target = new File(value);
                                   if (!target.isDirectory())
                                       throw new IOException("目录不存在或不可访问");
                                   current[0] = target;
                                   scan[0].run();
                               });
                           else if (Environment.isExternalStorageManager())
                               toast("已获得共享存储访问权限");
                           else
                               startActivity(new Intent(
                                   Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                   Uri.parse("package:" + getPackageName())));
                       })
                   .setNegativeButton("取消", null)
                   .show();
        tool(toolbar, "new", "新建文件夹", () -> nameDialog(current[0], scan[0]));
        tool(toolbar, "up", "上一级目录", () -> {
            File up = current[0].getParentFile();
            if (up != null) {
                current[0] = up;
                scan[0].run();
            }
        });
        tool(toolbar, "sort", "切换名称 / 时间排序", () -> {
            byTime[0] = !byTime[0];
            scan[0].run();
        });
        tool(toolbar, "folder", "切换位置与权限", locations);
        empty.setOnClickListener(v -> locations.run());
        path.setOnClickListener(v -> locations.run());
        list.setOnItemClickListener((a, v, i, id) -> {
            File f = entries.get(i);
            if (f.isDirectory()) {
                current[0] = f;
                scan[0].run();
            } else if (files) {
                d.dismiss();
                chosen.choose(f);
            }
        });
        list.setOnItemLongClickListener((a, v, i, id) -> {
            if (CaptureService.active)
                toast("抓包期间不能重命名文件或目录");
            else
                nameDialog(current[0], entries.get(i), scan[0]);
            return true;
        });
        if (!files) {
            LinearLayout footer = row();
            action(footer, "取消", false, () -> d.dismiss());
            action(footer, "使用此目录", true, () -> {
                File f = current[0];
                if (!f.isDirectory() || !f.canWrite()) {
                    toast("目录不可写，请选择其他位置或授予访问权限");
                    return;
                }
                chosen.choose(f);
                d.dismiss();
            });
            box.addView(footer);
        }
        sizeDialog(d, true);
        scan[0].run();
    }
    private interface TextSaved {
        void save(String value) throws Exception;
    }
    private void editTextDialog(String title, String value, TextSaved save) {
        LinearLayout box = col();
        box.setPadding(dp(14), dp(4), dp(14), dp(8));
        LinearLayout head = modalHead(box, title);
        LinearLayout content = modalBody(box);
        EditText input = input(title);
        input.setText(value);
        input.selectAll();
        content.addView(input);
        Dialog d = dialog("", box);
        tool(head, "close", "关闭", () -> d.dismiss());
        LinearLayout footer = row();
        action(footer, "取消", false, () -> d.dismiss());
        action(footer, "确定", true, () -> {
            try {
                save.save(input.getText().toString().trim());
                d.dismiss();
            } catch (Exception e) {
                input.setError(e.getMessage());
            }
        });
        box.addView(footer);
        sizeEditor(d, 200);
    }
    private void nameDialog(File parent, Runnable refresh) {
        nameDialog(parent, null, refresh);
    }
    private void nameDialog(File parent, File original, Runnable refresh) {
        editTextDialog(original == null ? "新建文件夹" : "重命名",
            original == null ? "" : original.getName(), value -> {
                SessionLibrary.validLeaf(value, 180);
                if (original != null && CaptureService.active)
                    throw new IOException("请先停止抓包再更改文件目录");
                File target = new File(parent, value);
                if (target.exists())
                    throw new IOException("已存在同名项目");
                boolean ok = original == null ? target.mkdir() : original.renameTo(target);
                if (!ok)
                    throw new IOException("操作失败，请检查目录权限");
                refresh.run();
            });
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
        LinearLayout box = col();
        box.setPadding(dp(14), dp(6), dp(14), dp(10));
        LinearLayout head = modalHead(box, "Root 与抓包核心");
        LinearLayout content = modalBody(box);
        TextView result = text("检查中…", 13, MUTED);
        result.setTextIsSelectable(true);
        content.addView(result);
        Dialog d = dialog("", box);
        tool(head, "close", "关闭检查", () -> d.dismiss());
        sizeEditor(d, 260);
        processIo.execute(() -> {
            try {
                String report = catalog.probe();
                post(() -> {
                    if (d.isShowing()) {
                        result.setText("检查通过\n\n" + report);
                        result.setTextColor(TEXT);
                    }
                });
            } catch (Exception e) {
                post(() -> {
                    if (d.isShowing()) {
                        result.setText("检查未通过\n\n" + e.getMessage());
                        result.setTextColor(RED);
                    }
                });
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
        if (tab == 2 && historyManaging) {
            historyManaging = false;
            historySelection.clear();
            showTab(2);
            return;
        }
        if (tab == 2 && browserDir != null && !browserDir.equals(browserRoot)) {
            enterDirectory(browserDir.getParentFile());
            return;
        }
        if (tab != 0) {
            showTab(0);
            return;
        }
        collapse();
    }
}
