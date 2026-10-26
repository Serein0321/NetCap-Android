package io.github.netcap.app;
import android.content.Context;
import android.net.*;
import java.util.Set;
import java.util.concurrent.Executor;
import org.json.*;

/** API36 公共回调隔离在本类，旧系统不加载新平台类型。只观察，不启停热点。 */
@android.annotation.TargetApi(36)
final class Api36Tethering implements AutoCloseable {
    final TetheringManager manager;
    final TetheringManager.TetheringEventCallback callback;
    Api36Tethering(Context c, NetworkTimeline log, Executor executor) throws Exception {
        manager = c.getSystemService(TetheringManager.class);
        if (manager == null)
            throw new IllegalStateException("TetheringManager unavailable");
        callback = new TetheringManager.TetheringEventCallback() {
            @Override
            public void onTetheredInterfacesChanged(Set<TetheringInterface> interfaces) {
                try {
                    JSONArray list = new JSONArray();
                    boolean wifi = false;
                    for (TetheringInterface i : interfaces) {
                        boolean isWifi = i.getType() == TetheringManager.TETHERING_WIFI;
                        wifi |= isWifi;
                        list.put(new JSONObject()
                                .put("name", i.getInterface())
                                .put("type", i.getType())
                                .put("wifiHotspot", isWifi));
                    }
                    log.write(new JSONObject()
                            .put("event", "tethering-interfaces")
                            .put("source", "api36-public-callback")
                            .put("wifiHotspotActive", wifi)
                            .put("interfaces", list));
                    log.snapshot();
                } catch (Exception e) {
                    log.error = e.toString();
                }
            }
        };
        manager.registerTetheringEventCallback(executor, callback);
        log.write(new JSONObject()
                .put("event", "tethering-monitor")
                .put("status", "available")
                .put("source", "api36-public-callback"));
    }
    public void close() {
        manager.unregisterTetheringEventCallback(callback);
    }
}
