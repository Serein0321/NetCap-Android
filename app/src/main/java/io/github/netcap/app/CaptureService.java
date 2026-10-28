package io.github.netcap.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.Process;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** 会话不依赖 Activity；控制通道为继承的 stdin/stdout，不开放任意 Root 命令接口。 */
public final class CaptureService extends Service {
    static final String START = "io.github.netcap.START", STOP = "io.github.netcap.STOP";
    static final String TOOL_START = "io.github.netcap.TOOL_START",
                        SHOW_WORKSPACE = "io.github.netcap.SHOW_WORKSPACE";
    static final Object LOCK = new Object();
    static JSONObject live = new JSONObject();
    static File activeSession;
    static volatile boolean active = false, stopping = false;
    private volatile Process child;
    private volatile boolean requestedStop;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final ScheduledExecutorService deadlines =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "root-command-deadline");
            t.setDaemon(true);
            return t;
        });
    private WindowManager wm;
    private Context overlayContext;
    private CaptureBubble ball;
    private int ballAnchorX, ballAnchorY;
    private boolean ballDragging;
    private final Runnable ballTick = new Runnable() {
        public void run() {
            if (ball == null || exiting)
                return;
            updateBall();
            if (ball != null && ball.expanded())
                windows.postDelayed(this, 1000);
        }
    };
    private WindowManager.LayoutParams ballParams;
    private static volatile CaptureService instance;
    private WorkspaceUi workspace;
    private boolean exiting;
    private final Handler windows = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    static boolean isRunning() {
        return instance != null;
    }
    void openWorkspace() {
        if (exiting)
            return;
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先在启动页授予悬浮窗权限", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            if (workspace == null)
                workspace = new WorkspaceUi(this);
            removeBall();
            workspace.show();
            if (!workspace.isVisible())
                showBall();
        } catch (Exception e) {
            if (workspace != null) {
                workspace.destroy();
                workspace = null;
            }
            Toast.makeText(this, "悬浮窗口打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            showBall();
        }
    }
    void collapseWorkspace() {
        if (workspace != null)
            workspace.hide();
        showBall();
    }
    void workspaceDismissed() {
        if (!exiting)
            showBall();
    }
    void exitTool() {
        exiting = true;
        if (workspace != null)
            workspace.hide();
        removeBall();
        if (active)
            requestStop();
        else
            finishTool();
    }
    private void finishTool() {
        removeBall();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    /** Root 管理器的普通授权页可能在悬浮层下面：请求期间临时隐藏，完成后按原顺序恢复。 */
    static final class RootWindowLease implements AutoCloseable {
        private final CaptureService owner;
        private WorkspaceUi view;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean();
        private RootWindowLease(CaptureService s, WorkspaceUi v) {
            owner = s;
            view = v;
        }
        static RootWindowLease pause() throws Exception {
            CaptureService s = instance;
            if (s == null)
                return new RootWindowLease(null, null);
            RootWindowLease lease = new RootWindowLease(s, null);
            CountDownLatch ready = new CountDownLatch(1);
            Runnable pause = () -> {
                try {
                    if (lease.closed.get())
                        return;
                    lease.view = s.workspace;
                    if (lease.view != null)
                        lease.view.pauseForRoot();
                    s.showBall();
                } finally {
                    ready.countDown();
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper())
                pause.run();
            else
                s.windows.post(pause);
            try {
                if (!ready.await(5, TimeUnit.SECONDS))
                    throw new IOException("界面响应超时");
                return lease;
            } catch (Exception e) {
                lease.close();
                throw new IOException("无法暂停悬浮窗口以进行 Root 授权，请稍后重试", e);
            }
        }
        public void close() {
            if (owner == null || !closed.compareAndSet(false, true))
                return;
            owner.windows.post(() -> {
                try {
                    if (view != null)
                        view.resumeFromRoot();
                } catch (Exception e) {
                    if (view != null)
                        view.destroy();
                    if (owner.workspace == view)
                        owner.workspace = null;
                    Toast
                        .makeText(owner, "无法恢复悬浮窗口，请检查悬浮窗权限：" + e.getMessage(),
                            Toast.LENGTH_LONG)
                        .show();
                }
                owner.removeBall();
                owner.showBall();
            });
        }
    }
    private synchronized void lockCpu() {
        if (wakeLock != null || !getSharedPreferences("settings", 0).getBoolean("wake", true))
            return;
        wakeLock = getSystemService(PowerManager.class)
                       .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetCapture:recording");
        wakeLock.acquire();
    }
    private synchronized void releaseCpu() {
        if (wakeLock != null) {
            if (wakeLock.isHeld())
                wakeLock.release();
            wakeLock = null;
        }
    }
    static JSONObject snapshot() {
        synchronized (LOCK) {
            try {
                return new JSONObject(live.toString());
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
    static Process root(Context c, String args) throws IOException {
        File core = new File(c.getApplicationInfo().nativeLibraryDir, "libnetcap_core.so");
        if (!core.isFile())
            throw new IOException("APK 内的原生核心未解包，请重新安装完整 APK");
        return new ProcessBuilder("su", "-c", quote(core.getAbsolutePath()) + " " + args)
            .redirectErrorStream(true)
            .start();
    }
    static List<JSONObject> processes(Context c) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        String error = "";
        for (String s : command(c, "processes")) {
            try {
                JSONObject j = new JSONObject(s);
                if (j.has("pid"))
                    result.add(j);
                else if (j.optString("event").equals("error"))
                    error = j.optString("message");
            } catch (JSONException e) {
                if (error.length() < 1024)
                    error += s + "\n";
            }
        }
        if (!error.isEmpty())
            throw new IOException(error);
        return result;
    }
    // 为什么：拒绝或未响应 Root 授权时，进程读取不能永久占用后台查询线程。
    static List<String> command(Context c, String args) throws Exception {
        try (RootWindowLease window = RootWindowLease.pause()) {
            return runCommand(c, args);
        }
    }
    private static List<String> runCommand(Context c, String args) throws Exception {
        Process p = root(c, args);
        java.util.concurrent.atomic.AtomicBoolean expired =
            new java.util.concurrent.atomic.AtomicBoolean();
        ScheduledFuture<?> timeout = deadlines.schedule(() -> {
            expired.set(true);
            p.destroy();
        }, 45, TimeUnit.SECONDS);
        List<String> lines = new ArrayList<>();
        try {
            try (BufferedReader in = new BufferedReader(
                     new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (lines.size() >= 20000)
                        throw new IOException("Root 输出超过预期范围");
                    lines.add(line);
                }
            }
            int code = p.waitFor();
            if (expired.get())
                throw new IOException("Root 授权请求超时，请检查 Root 管理器");
            if (code != 0)
                throw new IOException(lines.isEmpty() ? "Root 核心执行失败 (" + code + ")"
                                                      : String.join("\n", lines));
            return lines;
        } finally {
            timeout.cancel(false);
            if (p.isAlive())
                p.destroy();
        }
    }
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
            new NotificationChannel("capture", "抓包状态", NotificationManager.IMPORTANCE_LOW));
        overlayContext =
            createWindowContext(getSystemService(android.hardware.display.DisplayManager.class)
                                    .getDisplay(Display.DEFAULT_DISPLAY),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        wm = overlayContext.getSystemService(WindowManager.class);
    }
    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getService(this, 0,
            new Intent(this, CaptureService.class).setAction(SHOW_WORKSPACE),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, CaptureService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "capture")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("NetCap")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "停止并保存", stop).build())
            .build();
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null)
            return START_NOT_STICKY;
        if (STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if ("CONFIG".equals(intent.getAction())) {
            if (workspace != null)
                workspace.allowScreenCapture();
            removeBall();
            showBall();
            return START_NOT_STICKY;
        }
        if (TOOL_START.equals(intent.getAction()) || "SHOW_BALL".equals(intent.getAction())
            || SHOW_WORKSPACE.equals(intent.getAction())) {
            exiting = false;
            startForeground(10,
                notification(active ? "正在记录 · 点击悬浮球展开" : "工具待命 · 点击悬浮球展开"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            if (SHOW_WORKSPACE.equals(intent.getAction()))
                openWorkspace();
            else
                collapseWorkspace();
            return START_NOT_STICKY;
        }
        if (!START.equals(intent.getAction()))
            return START_NOT_STICKY;
        if (active)
            return START_NOT_STICKY;
        active = true;
        stopping = false;
        requestedStop = false;
        synchronized (LOCK) {
            live = new JSONObject();
        }
        startForeground(10, notification("等待 Root 核心启动"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        String path = intent.getStringExtra("path"), label = intent.getStringExtra("label");
        int uid = intent.getIntExtra("uid", -1), proto = intent.getIntExtra("protocol", 0),
            iface = intent.getIntExtra("iface", 0);
        worker.execute(() -> capture(path, label, uid, proto, iface));
        showBall();
        return START_NOT_STICKY;
    }
    private void capture(String path, String label, int uid, int proto, int iface) {
        JSONObject meta = new JSONObject();
        File session = null;
        boolean terminal = false;
        RootWindowLease authorization = null;
        NetworkTimeline timeline = null;
        try {
            File base = new File(path);
            if (!base.isDirectory() && !base.mkdirs())
                throw new IOException("保存目录不可用");
            String prefix = getSharedPreferences("settings", 0)
                                .getString("prefix", "抓包")
                                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
            if (prefix.trim().isEmpty())
                prefix = "抓包";
            if (prefix.length() > 40)
                prefix = prefix.substring(0, 40);
            session = new File(base,
                prefix + "_"
                    + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                        .format(new Date())
                    + "_" + UUID.randomUUID().toString().substring(0, 6));
            if (!session.mkdir())
                throw new IOException("无法创建新的会话目录");
            activeSession = session;
            boolean keepUnknown =
                uid >= 0 && getSharedPreferences("settings", 0).getBoolean("keepUnknown", false);
            if (keepUnknown) {
                File unknown = new File(session, "unattributed");
                if (!unknown.mkdir())
                    throw new IOException("无法创建归属未知数据目录");
                PacketStore.saveManifest(unknown,
                    new JSONObject()
                        .put("label", label + " · 归属未知")
                        .put("scope", "unknown")
                        .put("state", "capturing")
                        .put("started", System.currentTimeMillis()));
            }
            try {
                timeline = new NetworkTimeline(this, session, iface);
            } catch (Exception e) {
                meta.put("networkTimelineError", e.toString());
            }
            meta.put("label", label)
                .put("uid", uid)
                .put("scope", uid < 0 ? "whole-device" : "socket-association")
                .put("started", System.currentTimeMillis())
                .put("state", "starting")
                .put("protocol", proto)
                .put("interface", iface)
                .put("formatVersion", 2)
                .put("retainUnknown", keepUnknown)
                .put("pendingMillis", 2000)
                .put("pendingBytes", 8 * 1024 * 1024)
                .put("captureSource", "AF_PACKET/SOCK_DGRAM")
                .put("attribution",
                    uid < 0 ? "not-requested" : "best-effort; unknown flows excluded and counted");
            PacketStore.saveManifest(session, meta);
            publish(meta);
            authorization = RootWindowLease.pause();
            child = root(this,
                "capture " + quote(session.getAbsolutePath()) + " " + android.os.Process.myUid()
                    + " " + uid + " " + proto + " " + iface + " " + (keepUnknown ? "1" : "0"));
            java.util.concurrent.atomic.AtomicBoolean handshake =
                new java.util.concurrent.atomic.AtomicBoolean();
            final Process startedChild = child;
            ScheduledFuture<?> handshakeDeadline = deadlines.schedule(() -> {
                if (!handshake.get())
                    startedChild.destroy();
            }, 90, TimeUnit.SECONDS);
            if (requestedStop)
                requestStop();
            try (BufferedReader input = new BufferedReader(
                     new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                long lastSave = 0;
                while ((line = input.readLine()) != null) {
                    JSONObject j;
                    try {
                        j = new JSONObject(line);
                    } catch (JSONException e) {
                        meta.put("diagnostic", line);
                        continue;
                    }
                    String type = j.optString("event");
                    if (type.equals("ready")) {
                        authorization.close();
                        authorization = null;
                        handshake.set(true);
                        handshakeDeadline.cancel(false);
                        lockCpu();
                        meta.put("captureStarted", System.currentTimeMillis())
                            .put("captureElapsedStart", SystemClock.elapsedRealtime());
                        meta.put("state", requestedStop ? "stopping" : "capturing")
                            .put("message", j.optString("message"));
                    } else if (type.equals("stats") || type.equals("stopped")) {
                        Iterator<String> keys = j.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            meta.put(k, j.get(k));
                        }
                        if (type.equals("stopped")) {
                            terminal = true;
                            meta.put("captureDurationMillis",
                                Math.max(0,
                                    SystemClock.elapsedRealtime()
                                        - meta.optLong(
                                            "captureElapsedStart", SystemClock.elapsedRealtime())));
                            boolean loss = j.optLong("kernelDrops") > 0
                                || j.optLong("queueDrops") > 0 || j.optLong("truncated") > 0
                                || !j.optBoolean("kernelStatsAvailable")
                                || !j.optString("error").isEmpty();
                            meta.put("state", loss ? "attention" : "saved")
                                .put("finished", System.currentTimeMillis());
                        }
                    } else if (type.equals("error")) {
                        meta.put("error", j.optString("message")).put("state", "error");
                    }
                    publish(meta);
                    if (type.equals("stopped") || System.currentTimeMillis() - lastSave > 1000) {
                        PacketStore.saveManifest(session, meta);
                        lastSave = System.currentTimeMillis();
                    }
                    String note = "已记录 " + meta.optLong("written") + " 包 · "
                        + PacketStore.formatBytes(meta.optLong("bytes"));
                    getSystemService(NotificationManager.class).notify(10, notification(note));
                }
            }
            int code = child.waitFor();
            if (!terminal) {
                meta.put("state", "interrupted")
                    .put("error",
                        meta.optString("error").isEmpty()
                            ? "核心提前结束 (" + code + ")，已有文件保留，可从历史检查"
                            : meta.optString("error"));
            }
        } catch (Exception e) {
            try {
                meta.put("state", "error")
                    .put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            } catch (Exception ignored) {
            }
        } finally {
            if (timeline != null) {
                timeline.close();
                if (!timeline.error.isEmpty())
                    try {
                        meta.put("networkTimelineError", timeline.error);
                    } catch (Exception ignored) {
                    }
            }
            if (authorization != null)
                authorization.close();
            releaseCpu();
            try {
                if (meta.has("captureElapsedStart") && !meta.has("captureDurationMillis"))
                    meta.put("captureDurationMillis",
                        Math.max(0,
                            SystemClock.elapsedRealtime() - meta.optLong("captureElapsedStart")));
                meta.put("finished", System.currentTimeMillis());
                if (session != null)
                    PacketStore.saveManifest(session, meta);
            } catch (Exception e) {
                try {
                    meta.put("error", "会话状态保存失败: " + e.getMessage());
                } catch (Exception ignored) {
                }
            }
            try {
                if (session != null) {
                    File u = new File(session, "unattributed");
                    if (u.isDirectory()) {
                        JSONObject um = PacketStore.manifest(u)
                                            .put("state", meta.optString("state"))
                                            .put("finished", System.currentTimeMillis())
                                            .put("written", meta.optLong("unknownWritten"));
                        PacketStore.saveManifest(u, um);
                    }
                    stopping = true;
                    meta.put("stateBeforeReport", meta.optString("state"));
                    publish(new JSONObject(meta.toString()).put("state", "checking"));
                    IntegrityReport.generate(session, meta, () -> false);
                    if (u.isDirectory())
                        IntegrityReport.generate(u, PacketStore.manifest(u), () -> false);
                    meta.put("integrityReport", "integrity.json");
                    PacketStore.saveManifest(session, meta);
                }
            } catch (Exception e) {
                try {
                    meta.put("reportError", e.toString());
                    if (session != null)
                        PacketStore.saveManifest(session, meta);
                } catch (Exception ignored) {
                }
            }
            publish(meta);
            Process p = child;
            child = null;
            if (p != null)
                try {
                    p.getOutputStream().close();
                } catch (Exception ignored) {
                }
            active = false;
            stopping = false;
            new Handler(getMainLooper()).post(() -> {
                if (exiting)
                    finishTool();
                else {
                    getSystemService(NotificationManager.class)
                        .notify(10, notification("已停止并保存 · 点击展开工作台"));
                    showBall();
                }
            });
        }
    }
    private static void publish(JSONObject data) {
        synchronized (LOCK) {
            try {
                live = new JSONObject(data.toString());
            } catch (Exception ignored) {
            }
        }
        CaptureService owner = instance;
        if (owner != null)
            owner.windows.post(owner::refreshBall);
    }
    private void requestStop() {
        if (!active) {
            if (exiting)
                finishTool();
            return;
        }
        requestedStop = true;
        stopping = true;
        windows.post(this::refreshBall);
        Process p = child;
        if (p != null)
            try {
                p.getOutputStream().write("STOP\n".getBytes(StandardCharsets.US_ASCII));
                p.getOutputStream().flush();
            } catch (IOException ignored) {
            }
    }
    private int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }
    private void
    showBall() { // 为什么：使用系统悬浮窗口和触摸分发，不独占 /dev/input 或回注游戏触摸。
        if (exiting || (workspace != null && workspace.isVisible())
            || !Settings.canDrawOverlays(this))
            return;
        if (ball != null) {
            refreshBall();
            return;
        }
        ball = new CaptureBubble(overlayContext);
        ball.setOnClickListener(v -> openWorkspace());
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        ballParams = new WindowManager.LayoutParams(dp(52), dp(52),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        ballParams.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        ballParams.setFitInsetsTypes(0);
        ballParams.gravity = Gravity.TOP | Gravity.LEFT;
        ballAnchorX = getSharedPreferences("settings", 0).getInt("ballX", dp(12));
        ballAnchorY = getSharedPreferences("settings", 0).getInt("ballY", dp(180));
        ball.setState(
            BubbleState.from(snapshot(), active, stopping, SystemClock.elapsedRealtime()));
        placeBall();
        ball.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            int bx, by;
            boolean moved;
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        ballDragging = true;
                        startX = e.getRawX();
                        startY = e.getRawY();
                        bx = ballParams.x;
                        by = ballParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - startX, dy = e.getRawY() - startY;
                        if (Math.hypot(dx, dy) > dp(7))
                            moved = true;
                        if (moved) {
                            if (ball == null)
                                return true;
                            WindowMetrics metrics = wm.getCurrentWindowMetrics();
                            android.graphics.Rect bounds = metrics.getBounds();
                            android.graphics.Insets in =
                                metrics.getWindowInsets().getInsetsIgnoringVisibility(
                                    WindowInsets.Type.systemBars()
                                    | WindowInsets.Type.displayCutout());
                            BubbleLayout box = BubbleLayout.drag(bounds.width(), bounds.height(),
                                in.left, in.top, in.right, in.bottom, bx + (int) dx, by + (int) dy,
                                ballParams.width, ballParams.height, dp(4));
                            ballParams.x = box.x;
                            ballParams.y = box.y;
                            try {
                                wm.updateViewLayout(ball, ballParams);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        finishBallDrag();
                        if (!moved)
                            v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        finishBallDrag();
                        return true;
                }
                return true;
            }
        });
        try {
            wm.addView(ball, ballParams);
            refreshBall();
        } catch (Exception e) {
            ball = null;
        }
    }
    private void removeBall() {
        windows.removeCallbacks(ballTick);
        ballDragging = false;
        if (ball != null) {
            try {
                wm.removeView(ball);
            } catch (Exception ignored) {
            }
            ball = null;
        }
    }
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (workspace != null)
            workspace.resized();
        if (ball == null)
            return;
        if (ballDragging)
            finishBallDrag();
        placeBall();
        try {
            wm.updateViewLayout(ball, ballParams);
        } catch (Exception ignored) {
        }
    }
    private void refreshBall() {
        windows.removeCallbacks(ballTick);
        if (ball != null && !exiting)
            ballTick.run();
    }
    private void updateBall() {
        if (ball == null)
            return;
        ball.setState(
            BubbleState.from(snapshot(), active, stopping, SystemClock.elapsedRealtime()));
        if (!ballDragging) {
            int x = ballParams.x, y = ballParams.y, w = ballParams.width, h = ballParams.height;
            placeBall();
            if (x != ballParams.x || y != ballParams.y || w != ballParams.width
                || h != ballParams.height)
                try {
                    wm.updateViewLayout(ball, ballParams);
                } catch (Exception ignored) {
                }
        }
    }
    private void placeBall() {
        WindowMetrics metrics = wm.getCurrentWindowMetrics();
        android.graphics.Rect bounds = metrics.getBounds();
        android.graphics.Insets in = metrics.getWindowInsets().getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        BubbleLayout box = BubbleLayout.fit(bounds.width(), bounds.height(), in.left, in.top,
            in.right, in.bottom, ballAnchorX, ballAnchorY, dp(52), dp(ball.expanded() ? 148 : 52),
            dp(ball.expanded() ? 58 : 52), dp(4));
        ballParams.width = box.width;
        ballParams.height = box.height;
        ballParams.x = box.x;
        ballParams.y = box.y;
    }
    private void finishBallDrag() {
        ballDragging = false;
        if (ball == null)
            return;
        WindowMetrics metrics = wm.getCurrentWindowMetrics();
        android.graphics.Insets in = metrics.getWindowInsets().getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        ballAnchorX = BubbleLayout.anchorX(
            ballParams.x, ballParams.width, dp(52), metrics.getBounds().width(), in.left, in.right);
        ballAnchorY = ballParams.y + (ballParams.height - dp(52)) / 2;
        getSharedPreferences("settings", 0)
            .edit()
            .putInt("ballX", ballAnchorX)
            .putInt("ballY", ballAnchorY)
            .apply();
        refreshBall();
    }
    @Override
    public void onDestroy() {
        exiting = true;
        instance = null;
        if (workspace != null) {
            workspace.destroy();
            workspace = null;
        }
        releaseCpu();
        if (active)
            requestStop();
        removeBall();
        worker.shutdown();
        super.onDestroy();
    }
    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
