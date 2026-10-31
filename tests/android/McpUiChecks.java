package io.github.netcap.app;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Separate instrumentation APK: real home, clipboard, service and HTTP lifecycle. */
public final class McpUiChecks extends Instrumentation {
    private int count;
    private Activity home;
    private Context context;
    private File output;
    private boolean sdkHold;
    @Override public void onCreate(Bundle args) { super.onCreate(args); sdkHold = args != null && "true".equals(args.getString("sdkHold")); start(); }
    @Override public void callActivityOnResume(Activity a) { super.callActivityOnResume(a); if (a instanceof MainActivity) home = a; }
    private void require(boolean ok, String message) throws Exception { count++; if (!ok) throw new Exception(message); }
    private View find(View root, String title) {
        if (root instanceof TextView && title.equals(((TextView) root).getText().toString())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View v = find(((ViewGroup) root).getChildAt(i), title); if (v != null) return v; }
        return null;
    }
    private void click(String title) throws Exception {
        final boolean[] found = {false}; runOnMainSync(() -> { View v = find(home.getWindow().getDecorView(), title); if (v != null) { found[0] = true; v.performClick(); } }); require(found[0], "button: " + title); waitForIdleSync();
    }
    private void pause() { try { Thread.sleep(1800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private void waitState(String state) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (!McpService.state.equals(state) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        require(McpService.state.equals(state), "service state " + state + " actual=" + McpService.state + " " + McpService.error); pause();
    }
    private void screenshot(String name) throws Exception {
        waitForIdleSync(); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "screenshot");
        try (FileOutputStream out = new FileOutputStream(new File(output, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } bitmap.recycle();
    }
    private void shell(String command) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))) { byte[] bytes = new byte[1024]; while (in.read(bytes) >= 0) {} }
        pause();
    }
    private void top() { runOnMainSync(() -> { ScrollView v = scroll(home.getWindow().getDecorView()); if (v != null) v.scrollTo(0, 0); }); pause(); }
    private ScrollView scroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { ScrollView v = scroll(((ViewGroup) view).getChildAt(i)); if (v != null) return v; }
        return null;
    }
    private JSONObject rpc(String method, JSONObject params) throws Exception {
        byte[] body = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString().getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", 18765)) {
            socket.setSoTimeout(5000);
            String headers = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + McpSettings.token(context) + "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nContent-Length: " + body.length + "\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(body);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            require(in.readLine().startsWith("HTTP/1.1 200"), "HTTP status");
            while (!in.readLine().isEmpty()) {}
            return new JSONObject(in.readLine());
        }
    }
    private ClipData clipboard() {
        final ClipData[] value = {null}; runOnMainSync(() -> value[0] = context.getSystemService(ClipboardManager.class).getPrimaryClip()); return value[0];
    }
    private void editPort(String value) { runOnMainSync(() -> ((EditText) home.findViewById(1001)).setText(value)); }
    private void fixture() throws Exception {
        File root = new File(context.getExternalFilesDir(null), "captures/mcp-ui-fixture"); root.mkdirs();
        byte[] payload = "hello from Android MCP fixture".getBytes(StandardCharsets.UTF_8), packet = new byte[28 + payload.length];
        packet[0] = 0x45; packet[3] = (byte) packet.length; packet[9] = 17; packet[12] = 10; packet[15] = 1; packet[16] = 10; packet[19] = 2; packet[20] = 1; packet[22] = 2; packet[25] = (byte) (8 + payload.length); System.arraycopy(payload, 0, packet, 28, payload.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(0xa1b2c3d4).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(262144).putInt(101).array());
        out.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(1700000000).putInt(0).putInt(packet.length).putInt(packet.length).array()); out.write(packet);
        PacketStore.importPcap(new ByteArrayInputStream(out.toByteArray()), root, new JSONObject().put("label", "MCP 测试会话"));
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            context = getTargetContext(); output = new File(context.getExternalFilesDir(null), "mcp-checks"); output.mkdirs();
            McpSettings.prefs(context).edit().putInt("port", 18765).putBoolean("lan", false).putBoolean("notificationAsked", true).apply();
            fixture();
            home = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); pause(); top(); screenshot("home-stopped");
            require(!McpService.isRunning(), "MCP initially stopped");
            require(!CaptureService.isRunning(), "overlay not needed");
            editPort("80"); click("开启 MCP 服务"); require(!McpService.isRunning(), "invalid port cannot start");
            editPort("18765");
            final boolean[] errorCleared = {false}; runOnMainSync(() -> errorCleared[0] = ((EditText) home.findViewById(1001)).getError() == null); require(errorCleared[0], "port validation recovers");
            click("允许局域网连接");
            click("开启 MCP 服务"); waitState("running"); top(); screenshot("home-running");
            require(McpService.boundLan, "LAN binding");
            final boolean[] editable = {true}; runOnMainSync(() -> editable[0] = home.findViewById(1001).isEnabled()); require(!editable[0], "running settings locked");
            require(rpc("initialize", new JSONObject().put("protocolVersion", "2025-11-25")).getJSONObject("result").getString("protocolVersion").equals("2025-11-25"), "Android handshake");
            require(rpc("tools/list", new JSONObject()).getJSONObject("result").getJSONArray("tools").length() == 6, "Android tools/list");
            JSONObject sessions = rpc("tools/call", new JSONObject().put("name", "netcap_list_sessions").put("arguments", new JSONObject())).getJSONObject("result").getJSONObject("structuredContent");
            String id = sessions.getJSONArray("sessions").getJSONObject(0).getString("sessionId");
            require(rpc("tools/call", new JSONObject().put("name", "netcap_get_packet").put("arguments", new JSONObject().put("sessionId", id).put("row", 0))).getJSONObject("result").getJSONObject("structuredContent").getString("payloadUtf8Preview").contains("Android MCP fixture"), "Android payload");
            click("本机配置"); click("复制连接配置"); ClipData clip = clipboard();
            JSONObject config = new JSONObject(clip.getItemAt(0).getText().toString()).getJSONObject("mcpServers").getJSONObject("netcap");
            require(config.getString("url").equals("http://127.0.0.1:18765/mcp"), "clipboard URL");
            require(config.getJSONObject("headers").getString("Authorization").equals("Bearer " + McpSettings.token(context)), "clipboard authentication");
            require(clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"), "sensitive clipboard");
            runOnMainSync(() -> home.getWindow().getDecorView().findViewById(android.R.id.content).clearFocus());
            context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); pause();
            require(rpc("ping", new JSONObject()).has("result"), "service survives home background");
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); pause();
            runOnMainSync(() -> home.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)); pause(); top(); screenshot("home-landscape");
            require(rpc("ping", new JSONObject()).has("result"), "service survives rotation");
            runOnMainSync(() -> home.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)); pause();
            click("接入说明与访问令牌");
            runOnMainSync(() -> { View v = find(home.getWindow().getDecorView(), "访问令牌"); v.requestRectangleOnScreen(new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight()), true); }); pause(); screenshot("home-connection-details");
            click("停止 MCP 服务"); waitState("stopped");
            try (ServerSocket conflict = new ServerSocket(18765, 1, InetAddress.getByName("127.0.0.1"))) {
                click("开启 MCP 服务"); waitState("error"); require(McpService.error.contains("占用"), "port occupied error"); top(); screenshot("home-port-error");
            }
            shell("wm size 840x1800"); shell("settings put system font_scale 1.4"); top(); screenshot("home-small-large-font");
            shell("settings put secure show_ime_with_hard_keyboard 1");
            runOnMainSync(() -> { EditText input = home.findViewById(1001); input.requestFocus(); context.getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(input, 0); });
            pause();
            shell("screencap -p " + new File(output, "home-keyboard-system.png").getAbsolutePath());
            screenshot("home-keyboard");
            final boolean[] imeVisible = {false}; runOnMainSync(() -> imeVisible[0] = home.getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime())); require(imeVisible[0], "keyboard visible without hiding port");
            final boolean[] portVisible = {false}; final String[] bounds = {""}; runOnMainSync(() -> {
                View root = home.getWindow().getDecorView(), input = home.findViewById(1001);
                android.graphics.Rect rect = new android.graphics.Rect(); input.getGlobalVisibleRect(rect);
                portVisible[0] = rect.height() >= input.getHeight() && rect.bottom <= root.getHeight() - root.getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom;
                ScrollView viewport = scroll(root);
                bounds[0] = "rect=" + rect + " inputHeight=" + input.getHeight() + " rootHeight=" + root.getHeight() + " ime=" + root.getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom + " scrollY=" + viewport.getScrollY() + " viewportHeight=" + viewport.getHeight() + " padding=" + viewport.getPaddingBottom();
            }); require(portVisible[0], "port fully above keyboard " + bounds[0]);
            runOnMainSync(() -> context.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(home.getWindow().getDecorView().getWindowToken(), 0));
            shell("settings put system font_scale 1.0"); shell("wm size reset"); shell("settings put secure show_ime_with_hard_keyboard 0");
            click("重试开启 MCP"); waitState("running"); top(); screenshot("home-final");
            if (sdkHold) {
                File ready = new File(context.getCacheDir(), "mcp-sdk-ready"), done = new File(context.getCacheDir(), "mcp-sdk-done");
                ready.createNewFile();
                long deadline = System.currentTimeMillis() + 60000;
                while (!done.isFile() && System.currentTimeMillis() < deadline) Thread.sleep(100);
                require(done.isFile(), "SDK verification completion marker");
                ready.delete(); done.delete();
            }
            result.putString("result", "PASS " + count + " Android MCP checks");
            result.putString("screenshots", output.getAbsolutePath()); finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            try { shell("settings put system font_scale 1.0"); shell("wm size reset"); shell("settings put secure show_ime_with_hard_keyboard 0"); } catch (Exception ignored) {}
            result.putString("result", "FAIL: " + android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED, result);
        }
    }
}
