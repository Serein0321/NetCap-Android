package io.github.netcap.app;
import android.content.Context;
import android.net.*;
import java.io.*;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** 系统网络回调与接口快照。不能获得热点角色时记录 unavailable，不据名称猜测。 */
final class NetworkTimeline implements AutoCloseable {
    final ConnectivityManager manager;
    final FileOutputStream file;
    final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    boolean closed;
    String previous = "";
    volatile String error = "";
    AutoCloseable tethering;
    final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        public void onAvailable(Network n) {
            network("available", n);
        }
        public void onLost(Network n) {
            network("lost", n);
        }
        public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
            network("capabilities", n);
        }
        public void onLinkPropertiesChanged(Network n, LinkProperties l) {
            network("link", n);
        }
    };
    final ConnectivityManager.NetworkCallback defaults = new ConnectivityManager.NetworkCallback() {
        public void onAvailable(Network n) {
            network("default-available", n);
        }
        public void onLost(Network n) {
            network("default-lost", n);
        }
        public void onLinkPropertiesChanged(Network n, LinkProperties l) {
            network("default-link", n);
        }
    };
    NetworkTimeline(Context c, File session, int iface) throws Exception {
        manager = c.getSystemService(ConnectivityManager.class);
        file = new FileOutputStream(new File(session, "network-events.jsonl"));
        write(new JSONObject()
                .put("event", "capture-scope")
                .put("boundInterface", iface)
                .put("directions", "incoming,outgoing")
                .put("hotspotRole",
                    android.os.Build.VERSION.SDK_INT >= 36 ? "pending" : "unavailable"));
        snapshot();
        try {
            manager.registerNetworkCallback(
                new NetworkRequest.Builder().clearCapabilities().build(), callback);
        } catch (Exception e) {
            error = e.toString();
            write(new JSONObject().put("event", "callback-unavailable").put("error", error));
        }
        try {
            manager.registerDefaultNetworkCallback(defaults);
        } catch (Exception e) {
            error = e.toString();
            write(
                new JSONObject().put("event", "default-callback-unavailable").put("error", error));
        }
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            try {
                tethering = new Api36Tethering(c, this, r -> {
                    try {
                        timer.execute(r);
                    } catch (RejectedExecutionException ignored) {
                    }
                });
            } catch (Exception e) {
                write(new JSONObject()
                        .put("event", "tethering-monitor")
                        .put("status", "unavailable")
                        .put("reason", e.toString()));
            }
        } else
            write(new JSONObject()
                    .put("event", "tethering-monitor")
                    .put("status", "unavailable")
                    .put("reason", "requires-api36"));
        timer.scheduleWithFixedDelay(this::snapshot, 3, 3, TimeUnit.SECONDS);
    }
    synchronized void write(JSONObject j) {
        if (closed)
            return;
        try {
            j.put("epochMillis", System.currentTimeMillis());
            file.write((j.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            file.flush();
        } catch (Exception e) {
            error = e.toString();
        }
    }
    void network(String event, Network n) {
        try {
            NetworkCapabilities c = manager.getNetworkCapabilities(n);
            LinkProperties l = manager.getLinkProperties(n);
            JSONObject j = new JSONObject().put("event", event).put("network", n.toString());
            if (c != null)
                j.put("wifi", c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                    .put("cellular", c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                    .put("vpn", c.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
            if (l != null)
                j.put("interface", l.getInterfaceName())
                    .put("addresses", l.getLinkAddresses().toString());
            Network active = manager.getActiveNetwork();
            j.put("defaultNetwork", active == null ? "" : active.toString());
            write(j);
            snapshot();
        } catch (Exception e) {
            error = e.toString();
        }
    }
    void snapshot() {
        try {
            List<String> rows = new ArrayList<>();
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all != null)
                while (all.hasMoreElements()) {
                    NetworkInterface n = all.nextElement();
                    rows.add(new JSONObject()
                            .put("index", n.getIndex())
                            .put("name", n.getName())
                            .put("up", n.isUp())
                            .put("loopback", n.isLoopback())
                            .put("virtual", n.isVirtual())
                            .put("addresses", n.getInterfaceAddresses().toString())
                            .toString());
                }
            Collections.sort(rows);
            String signature = rows.toString();
            synchronized (this) {
                if (signature.equals(previous) || closed)
                    return;
                previous = signature;
            }
            JSONArray list = new JSONArray();
            for (String r : rows) list.put(new JSONObject(r));
            write(new JSONObject().put("event", "interfaces").put("interfaces", list));
        } catch (Exception e) {
            error = e.toString();
        }
    }
    public void close() {
        if (tethering != null)
            try {
                tethering.close();
            } catch (Exception e) {
                error = e.toString();
            }
        timer.shutdownNow();
        try {
            manager.unregisterNetworkCallback(callback);
        } catch (Exception ignored) {
        }
        try {
            manager.unregisterNetworkCallback(defaults);
        } catch (Exception ignored) {
        }
        snapshot();
        try {
            write(new JSONObject().put("event", "monitor-stopped"));
        } catch (JSONException ignored) {
        }
        synchronized (this) {
            if (closed)
                return;
            try {
                file.getFD().sync();
                file.close();
            } catch (Exception e) {
                error = e.toString();
            }
            closed = true;
        }
    }
}
