package io.github.netcap.app;

import android.content.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import org.json.*;

final class McpSettings {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("mcp", 0); }
    static int port(Context c) { return prefs(c).getInt("port", 8765); }
    static boolean lan(Context c) { return prefs(c).getBoolean("lan", false); }
    static synchronized String token(Context c) {
        String value = prefs(c).getString("token", "");
        if (!value.matches("[a-f0-9]{48}")) {
            byte[] data = new byte[24]; new SecureRandom().nextBytes(data);
            StringBuilder b = new StringBuilder();
            for (byte n : data) b.append(String.format(Locale.ROOT, "%02x", n & 255));
            value = b.toString(); prefs(c).edit().putString("token", value).apply();
        }
        return value;
    }
    static List<String> lanHosts() {
        List<String> hosts = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface n = interfaces.nextElement();
                String name = n.getName().toLowerCase(Locale.ROOT);
                if (!n.isUp() || n.isLoopback() || !(name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("eth") || name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("rndis") || name.startsWith("bnep"))) continue;
                Enumeration<InetAddress> addresses = n.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress a = addresses.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) hosts.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {}
        Collections.sort(hosts);
        return hosts;
    }
    static String url(String host, int port) { return "http://" + host + ":" + port + "/mcp"; }
    static String config(String url, String token) throws JSONException {
        return new JSONObject().put("mcpServers", new JSONObject().put("netcap", new JSONObject()
            .put("url", url).put("headers", new JSONObject().put("Authorization", "Bearer " + token)))).toString(2);
    }
}
