package io.github.netcap.app;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Instrumentation for the quiet home, its settings page and the real MCP transport. */
public final class McpUiChecks extends Instrumentation {
    private int checks;
    private MainActivity home;
    private Context context;
    private File output;
    private boolean sdkHold;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        sdkHold = args != null && "true".equals(args.getString("sdkHold"));
        start();
    }
    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (activity instanceof MainActivity) home = (MainActivity) activity;
    }
    private void require(boolean value, String message) throws Exception {
        checks++;
        if (!value) throw new Exception(message);
    }
    private void pause() { pause(900); }
    private void step(String name) { android.util.Log.i("McpUiChecks", name); }
    private void pause(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private void click(Activity activity, int id) throws Exception {
        final boolean[] clicked = {false};
        runOnMainSync(() -> {
            View view = activity.findViewById(id);
            if (view != null && view.isEnabled() && view.getVisibility() == View.VISIBLE) {
                clicked[0] = true;
                view.performClick();
            }
        });
        require(clicked[0], "clickable view " + id);
        waitForIdleSync();
        pause();
    }
    private void setText(Activity activity, int id, String value) throws Exception {
        final boolean[] done = {false};
        runOnMainSync(() -> {
            View view = activity.findViewById(id);
            if (view instanceof EditText) { ((EditText) view).setText(value); done[0] = true; }
        });
        require(done[0], "edit view " + id);
    }
    private void waitState(String state) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (!McpService.state.equals(state) && System.currentTimeMillis() < deadline) Thread.sleep(80);
        require(McpService.state.equals(state), "service state " + state + " actual=" + McpService.state + " " + McpService.error);
        pause();
    }
    private void screenshot(String name) throws Exception {
        waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null, "screenshot " + name);
        try (FileOutputStream out = new FileOutputStream(new File(output, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
    private void shell(String command) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[2048];
            while (in.read(buffer) >= 0) {}
        }
        pause();
    }
    private void top() {
        runOnMainSync(() -> {
            ScrollView scroll = scroll(home.getWindow().getDecorView());
            if (scroll != null) scroll.scrollTo(0, 0);
        });
        pause(300);
    }
    private ScrollView scroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            ScrollView found = scroll(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    private List<String> texts(Activity activity) {
        List<String> out = new ArrayList<>();
        collect(activity.getWindow().getDecorView(), out);
        return out;
    }
    private void collect(View view, List<String> out) {
        if (view instanceof TextView) out.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            collect(((ViewGroup) view).getChildAt(i), out);
    }
    private boolean hasText(Activity activity, String target) { return texts(activity).contains(target); }
    private String textStarting(Activity activity, String prefix, boolean notLoopback) {
        for (String value : texts(activity)) {
            if (value.startsWith(prefix) && (!notLoopback || !value.contains("127.0.0.1"))) return value;
        }
        return "";
    }
    private boolean activeWindowHasText(String target) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        return nodeHasText(root, target);
    }
    private boolean nodeHasText(AccessibilityNodeInfo node, String target) {
        if (node == null) return false;
        CharSequence text = node.getText(), description = node.getContentDescription();
        if ((text != null && text.toString().contains(target))
            || (description != null && description.toString().contains(target))) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (nodeHasText(node.getChild(i), target)) return true;
        return false;
    }
    private ClipData clipboard() {
        final ClipData[] clip = {null};
        runOnMainSync(() -> clip[0] = context.getSystemService(ClipboardManager.class).getPrimaryClip());
        return clip[0];
    }
    private static final class Response { int status; JSONObject body; }
    private Response rpc(String token, String method, JSONObject params) throws Exception {
        byte[] body = new JSONObject().put("jsonrpc", "2.0").put("id", 1)
            .put("method", method).put("params", params).toString().getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", McpService.boundPort)) {
            socket.setSoTimeout(6000);
            String headers = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + (token == null ? "" : "Authorization: Bearer " + token + "\r\n")
                + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                + "Content-Length: " + body.length + "\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Response response = new Response();
            response.status = Integer.parseInt(in.readLine().split(" ")[1]);
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {}
            StringBuilder payload = new StringBuilder();
            while ((line = in.readLine()) != null) payload.append(line);
            if (payload.length() > 0) response.body = new JSONObject(payload.toString());
            return response;
        }
    }
    private void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
    private void fixture() throws Exception {
        File root = new File(context.getExternalFilesDir(null), "captures/mcp-ui-fixture");
        delete(root);
        root.mkdirs();
        byte[] payload = "hello from Android MCP fixture".getBytes(StandardCharsets.UTF_8), packet = new byte[28 + payload.length];
        packet[0] = 0x45; packet[3] = (byte) packet.length; packet[9] = 17;
        packet[12] = 10; packet[15] = 1; packet[16] = 10; packet[19] = 2;
        packet[20] = 1; packet[22] = 2; packet[25] = (byte) (8 + payload.length);
        System.arraycopy(payload, 0, packet, 28, payload.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(0xa1b2c3d4).putShort((short) 2)
            .putShort((short) 4).putInt(0).putInt(0).putInt(262144).putInt(101).array());
        out.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(1700000000).putInt(0)
            .putInt(packet.length).putInt(packet.length).array());
        out.write(packet);
        PacketStore.importPcap(new ByteArrayInputStream(out.toByteArray()), root, new JSONObject().put("label", "MCP 测试会话"));
    }
    private McpSettingsActivity settings() throws Exception {
        McpSettingsActivity activity = (McpSettingsActivity) startActivitySync(
            new Intent(context, McpSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        pause();
        return activity;
    }
    private void closeSettings(Activity activity) {
        runOnMainSync(activity::finish);
        pause();
    }
    private void openSettingsFromHome() throws Exception {
        click(home, Ids.MCP_SETTINGS);
        require(activeWindowHasText("MCP 设置"), "gear opens MCP settings");
        require(activeWindowHasText("局域网访问"), "settings shows LAN row");
        require(activeWindowHasText("访问令牌"), "settings shows token row");
        getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        pause();
    }
    private void startMcp() throws Exception {
        click(home, Ids.MCP_TOGGLE);
        waitState("running");
    }
    private void stopMcp() throws Exception {
        click(home, Ids.MCP_STOP);
        waitState("stopped");
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            context = getTargetContext();
            output = new File(context.getExternalFilesDir(null), "mcp-checks");
            output.mkdirs();
            McpSettings.prefs(context).edit().putInt("port", 2845).putBoolean("lan", true)
                .putBoolean("auth", false).putBoolean("notificationAsked", true).remove("token").apply();
            fixture();
            home = (MainActivity) startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            pause();
            top();
            screenshot("home-stopped");
            step("home stopped");

            require(!McpService.isRunning(), "MCP starts stopped");
            require(!CaptureService.isRunning(), "capture is independent");
            require(hasText(home, "开启 MCP"), "single MCP start action");
            require(!hasText(home, "2845"), "port is not exposed on the home");
            require(textStarting(home, "http://", false).isEmpty(), "no address before start");
            require(home.findViewById(Ids.MCP_TOGGLE).isEnabled(), "start enabled");
            openSettingsFromHome();
            step("settings from gear");

            startMcp();
            step("mcp running without token");
            require(McpService.boundPort == 2845 && McpService.boundLan && !McpService.boundAuth, "default port, LAN on, no token");
            String local = ((TextView) home.findViewById(Ids.MCP_LOCAL)).getText().toString();
            require(local.equals("http://127.0.0.1:2845/mcp"), "local address");
            String lan = textStarting(home, "http://", true);
            require(lan.startsWith("http://") && lan.endsWith(":2845/mcp"), "LAN address visible: " + texts(home));
            require(hasText(home, "未启用访问令牌 · 同网设备可直接读取"), "token warning when open");
            screenshot("home-running");

            Response init = rpc(null, "initialize", new JSONObject().put("protocolVersion", "2025-11-25"));
            require(init.status == 200 && init.body.getJSONObject("result").getString("protocolVersion").equals("2025-11-25"), "open handshake");
            Response listed = rpc(null, "tools/list", new JSONObject());
            require(listed.status == 200 && listed.body.getJSONObject("result").getJSONArray("tools").length() == 6, "six read-only tools");
            JSONObject sessions = rpc(null, "tools/call", new JSONObject().put("name", "netcap_list_sessions").put("arguments", new JSONObject()))
                .body.getJSONObject("result").getJSONObject("structuredContent");
            String sessionId = sessions.getJSONArray("sessions").getJSONObject(0).getString("sessionId");
            JSONObject packet = rpc(null, "tools/call", new JSONObject().put("name", "netcap_get_packet")
                .put("arguments", new JSONObject().put("sessionId", sessionId).put("row", 0)))
                .body.getJSONObject("result").getJSONObject("structuredContent");
            require(packet.getString("payloadUtf8Preview").contains("Android MCP fixture"), "real fixture payload");

            click(home, Ids.MCP_LOCAL_ROW);
            ClipData address = clipboard();
            require(address != null && address.getItemAt(0).getText().toString().equals(local), "copy local address");

            McpSettingsActivity locked = settings();
            require(!locked.findViewById(Ids.SETTINGS_AUTH).isEnabled(), "auth locked while running");
            require(!locked.findViewById(Ids.SETTINGS_PORT).isEnabled(), "port locked while running");
            require(!locked.findViewById(Ids.SETTINGS_RESET_TOKEN).isEnabled(), "token regeneration locked while running");
            require(((TextView) locked.findViewById(Ids.SETTINGS_NOTE)).getText().toString().contains("运行中"), "locked note");
            screenshot("settings-locked");
            closeSettings(locked);
            step("settings locked");
            stopMcp();
            require(hasText(home, "开启 MCP"), "start action returns after stop");

            McpSettingsActivity auth = settings();
            click(auth, Ids.SETTINGS_AUTH);
            require(auth.findViewById(Ids.SETTINGS_TOKEN_BLOCK).getVisibility() == View.VISIBLE, "token block appears");
            String token = ((TextView) auth.findViewById(Ids.SETTINGS_TOKEN)).getText().toString();
            require(token.matches("[a-f0-9]{48}"), "48 hex token");
            click(auth, Ids.SETTINGS_COPY_TOKEN);
            ClipData secret = clipboard();
            require(secret != null && secret.getItemAt(0).getText().toString().equals(token), "copy token");
            require(secret.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"), "sensitive token clip");
            final boolean[] revealed = {false};
            click(auth, Ids.SETTINGS_REVEAL);
            runOnMainSync(() -> revealed[0] = ((TextView) auth.findViewById(Ids.SETTINGS_TOKEN)).getTransformationMethod() == null);
            require(revealed[0], "reveal token");
            setText(auth, Ids.SETTINGS_PORT, "18766");
            screenshot("settings-token");
            click(auth, Ids.SETTINGS_DONE);
            require(McpSettings.port(context) == 18766, "edited port saved");
            require(McpSettings.auth(context), "auth saved");

            startMcp();
            require(McpService.boundPort == 18766 && McpService.boundAuth, "edited port and token bound");
            require(rpc(null, "initialize", new JSONObject()).status == 401, "token required");
            require(rpc(token, "initialize", new JSONObject()).status == 200, "token accepted");
            require(hasText(home, "已启用访问令牌 · 在设置中查看"), "auth state visible");
            screenshot("home-running-auth");
            step("mcp running with token");
            stopMcp();

            McpSettingsActivity lanSettings = settings();
            click(lanSettings, Ids.SETTINGS_LAN);
            setText(lanSettings, Ids.SETTINGS_PORT, "18765");
            click(lanSettings, Ids.SETTINGS_DONE);
            require(!McpSettings.lan(context) && McpSettings.port(context) == 18765, "LAN off and port restored");
            startMcp();
            require(!McpService.boundLan, "local-only binding");
            require(textStarting(home, "http://", true).isEmpty(), "no LAN address when off");
            require(hasText(home, "局域网访问已关闭 · 可在设置中开启"), "LAN off hint");
            screenshot("home-local-only");
            step("local only");
            stopMcp();

            McpSettingsActivity lanOn = settings();
            click(lanOn, Ids.SETTINGS_LAN);
            click(lanOn, Ids.SETTINGS_DONE);
            require(McpSettings.lan(context), "LAN restored");

            try (ServerSocket conflict = new ServerSocket(18765)) {
                click(home, Ids.MCP_TOGGLE);
                waitState("error");
                require(McpService.error.contains("占用"), "port conflict message");
                screenshot("home-port-error");
            }
            click(home, Ids.MCP_TOGGLE);
            waitState("running");
            step("port conflict retry");

            runOnMainSync(() -> home.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            pause(1400);
            top();
            screenshot("home-landscape");
            step("landscape");
            require(rpc(token, "ping", new JSONObject()).status == 200, "survives rotation");
            runOnMainSync(() -> home.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            pause(1200);
            top();

            context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            pause(800);
            require(rpc(token, "ping", new JSONObject()).status == 200, "survives background");
            context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            pause(1800);
            require(home != null && !home.isFinishing(), "home resumed after background");
            top();
            step("background and resume");

            shell("wm size 840x1800");
            shell("settings put system font_scale 1.4");
            pause(900);
            top();
            screenshot("home-small-large-font");
            step("small screen large font");
            require(hasText(home, "MCP 连接"), "sections remain readable at 320dp");
            require(home.findViewById(Ids.MCP_LOCAL_ROW).getWidth() > 0, "address row remains laid out");
            shell("wm size reset");
            shell("settings put system font_scale 1.0");
            pause(900);
            top();

            stopMcp();
            McpSettingsActivity keyboard = settings();
            shell("settings put secure show_ime_with_hard_keyboard 1");
            runOnMainSync(() -> keyboard.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE));
            final EditText port = keyboard.findViewById(Ids.SETTINGS_PORT);
            final boolean[] shown = {false};
            runOnMainSync(() -> {
                port.performClick();
                port.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                    keyboard.getSystemService(android.view.inputmethod.InputMethodManager.class);
                shown[0] = imm.showSoftInput(port, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
            });
            step("ime requested shown=" + shown[0] + " focus=" + port.hasFocus());
            pause(2200);
            screenshot("settings-keyboard");
            step("settings keyboard");
            final boolean[] visible = {false};
            final String[] detail = {""};
            runOnMainSync(() -> {
                View root = keyboard.getWindow().getDecorView();
                Rect rect = new Rect();
                port.getGlobalVisibleRect(rect);
                int ime = root.getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom;
                visible[0] = ime > 0 && rect.height() >= port.getHeight() && rect.bottom <= root.getHeight() - ime;
                detail[0] = "ime=" + ime + " rect=" + rect + " root=" + root.getHeight() + " portHeight=" + port.getHeight();
            });
            require(visible[0], "port visible above keyboard " + detail[0]);
            shell("settings put secure show_ime_with_hard_keyboard 0");
            closeSettings(keyboard);
            shell("wm size reset");
            shell("settings put system font_scale 1.0");

            startMcp();
            require(McpService.boundPort == 18765 && McpService.boundAuth && McpService.isRunning(), "final endpoint ready");
            screenshot("home-final");
            step("final");

            if (sdkHold) {
                File ready = new File(context.getCacheDir(), "mcp-sdk-ready");
                File done = new File(context.getCacheDir(), "mcp-sdk-done");
                ready.createNewFile();
                long deadline = System.currentTimeMillis() + 60000;
                while (!done.isFile() && System.currentTimeMillis() < deadline) Thread.sleep(100);
                require(done.isFile(), "SDK verification completion marker");
                ready.delete();
                done.delete();
            }
            result.putString("result", "PASS " + checks + " Android MCP checks");
            result.putString("screenshots", output.getAbsolutePath());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            try {
                shell("settings put system font_scale 1.0");
                shell("wm size reset");
                shell("settings put secure show_ime_with_hard_keyboard 0");
            } catch (Exception ignored) {}
            result.putString("result", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
