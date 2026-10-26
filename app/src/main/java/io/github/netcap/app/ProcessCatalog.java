package io.github.netcap.app;
import android.content.Context;
import java.io.*;
import java.lang.Process;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** 单线程使用的只读查询通道；工具退出关闭 stdin，使 Root 子进程退出。 */
final class ProcessCatalog implements AutoCloseable {
    final Context context;
    final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    volatile Process child;
    volatile boolean closed;
    BufferedReader in;
    BufferedWriter out;
    ProcessCatalog(Context c) {
        context = c;
    }
    List<JSONObject> snapshot() throws Exception {
        if (closed)
            throw new IOException("查询已关闭");
        CaptureService.RootWindowLease lease = null;
        ScheduledFuture<?> expiry = timer.schedule(() -> {
            Process p = child;
            if (p != null) {
                try {
                    p.getOutputStream().close();
                } catch (Exception ignored) {
                }
                p.destroyForcibly();
            }
        }, 45, TimeUnit.SECONDS);
        try {
            if (child == null || !child.isAlive()) {
                lease = CaptureService.RootWindowLease.pause();
                synchronized (this) {
                    if (closed)
                        throw new IOException("查询已关闭");
                    child = CaptureService.root(context, "processes-stream");
                }
                in = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
                out = new BufferedWriter(
                    new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
                boolean ready = false;
                for (int i = 0; i < 100; i++) {
                    String line = in.readLine();
                    if (line == null)
                        break;
                    JSONObject j;
                    try {
                        j = new JSONObject(line);
                    } catch (JSONException e) {
                        continue;
                    }
                    if (j.optString("event").equals("error"))
                        throw new IOException(j.optString("message"));
                    if (j.optString("event").equals("catalog-ready")) {
                        ready = true;
                        break;
                    }
                }
                if (!ready)
                    throw new IOException("Root 进程查询启动失败或授权超时");
                lease.close();
                lease = null;
            }
            out.write("list\n");
            out.flush();
            List<JSONObject> data = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                JSONObject j = new JSONObject(line);
                if (j.optString("event").equals("catalog-end"))
                    return data;
                if (j.optString("event").equals("error"))
                    throw new IOException(j.optString("message"));
                if (j.has("pid"))
                    data.add(j);
                if (data.size() > 20000)
                    throw new IOException("进程数超出范围");
            }
            throw new IOException("进程查询通道已断开");
        } catch (Exception e) {
            stopChild();
            throw e;
        } finally {
            expiry.cancel(false);
            if (closed)
                stopChild();
            if (lease != null)
                lease.close();
        }
    }
    void stopChild() {
        Process p = child;
        child = null;
        if (p != null) {
            try {
                p.getOutputStream().close();
            } catch (Exception ignored) {
            }
            p.destroy();
        }
    }
    public synchronized void close() {
        closed = true;
        stopChild();
        timer.shutdownNow();
    }
}
