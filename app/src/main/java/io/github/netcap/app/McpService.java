package io.github.netcap.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Explicitly started, separately stoppable service; never creates overlay windows. */
public final class McpService extends Service {
    static final String START = "io.github.netcap.MCP_START", STOP = "io.github.netcap.MCP_STOP";
    private static final int NOTIFICATION = 42;
    static volatile String state = "stopped", error = "";
    static volatile int boundPort;
    static volatile boolean boundLan;
    static volatile long started;
    private static volatile McpServer running;
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;
    static boolean busy() { return state.equals("starting") || state.equals("stopping"); }
    static boolean isRunning() { return state.equals("running"); }
    static long calls() { McpServer s = running; return s == null ? 0 : s.calls.get(); }
    static long lastRequest() { McpServer s = running; return s == null ? 0 : s.lastRequest; }
    static String lastTool() { McpServer s = running; return s == null ? "" : s.lastTool; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (STOP.equals(intent.getAction())) { shutdown(); return START_NOT_STICKY; }
        if (!START.equals(intent.getAction()) || isRunning() || busy()) return START_NOT_STICKY;
        state = "starting"; error = "";
        boundPort = McpSettings.port(this); boundLan = McpSettings.lan(this);
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("mcp", "MCP 连接服务", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 41, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop = PendingIntent.getService(this, 42, new Intent(this, McpService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "mcp").setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("NetCap · MCP 服务").setContentText("只读访问 · 端口 " + boundPort + (boundLan ? " · 局域网" : " · 仅本机"))
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "停止 MCP", stop).build()).build();
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (Exception e) { fail("无法启动后台服务，请回到首页重试"); return START_NOT_STICKY; }
        final int port = boundPort; final boolean lan = boundLan; final String token = McpSettings.token(this);
        lifecycle.execute(() -> {
            try {
                McpTools tools = new McpTools(new McpTools.Source() {
                    public List<File> roots() {
                        Set<File> out = new LinkedHashSet<>();
                        File external = getExternalFilesDir(null);
                        if (external != null) out.add(new File(external, "captures"));
                        SharedPreferences p = getSharedPreferences("settings", 0);
                        String directory = p.getString("directory", "");
                        if (!directory.isEmpty()) out.add(new File(directory));
                        for (String root : p.getStringSet("roots", Collections.emptySet())) out.add(new File(root));
                        return new ArrayList<>(out);
                    }
                    public JSONObject status() throws Exception {
                        return new JSONObject().put("captureRunning", CaptureService.active).put("captureStopping", CaptureService.stopping)
                            .put("capture", CaptureService.snapshot()).put("mcpPort", boundPort).put("lanEnabled", boundLan)
                            .put("toolCalls", calls()).put("readOnly", true);
                    }
                });
                McpServer server = new McpServer(port, lan, token, tools, message -> main.post(() -> { if (!destroyed) fail(message); }));
                if (destroyed || !state.equals("starting")) { server.close(); return; }
                running = server;
                server.start();
                main.post(() -> {
                    if (destroyed || !state.equals("starting")) { server.close(); return; }
                    started = System.currentTimeMillis(); state = "running";
                });
            } catch (Exception e) {
                String message = e instanceof java.net.BindException ? "端口 " + port + " 已被占用，请更换端口后重试" : "监听失败，请检查端口和网络设置后重试";
                main.post(() -> { if (!destroyed && state.equals("starting")) fail(message); });
            }
        });
        return START_NOT_STICKY;
    }
    private void fail(String message) {
        error = message; state = "error";
        McpServer server = running; running = null;
        if (server != null) server.close();
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private void shutdown() {
        if (destroyed || state.equals("stopping")) return;
        state = "stopping";
        lifecycle.execute(() -> {
            McpServer server = running; running = null;
            if (server != null) server.close();
            main.post(() -> { state = "stopped"; error = ""; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); });
        });
    }
    @Override public void onDestroy() {
        destroyed = true;
        McpServer server = running; running = null;
        if (server != null) server.close();
        lifecycle.shutdownNow();
        if (!state.equals("error")) state = "stopped";
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
