package io.github.netcap.app;
import java.util.Locale;
import org.json.JSONObject;
/**
 * Small read-only presentation snapshot. Counters are main-session written packets, not ingress.
 */
final class BubbleState {
    enum Mode { IDLE, STARTING, RECORDING, STOPPING, CHECKING, ERROR }
    final Mode mode;
    final boolean expanded, attention;
    final long written, up, down, elapsed;
    final String label, clock, total, sent, received, description;
    private BubbleState(Mode mode, boolean expanded, boolean attention, long written, long up,
        long down, long elapsed, long unknown) {
        this.mode = mode;
        this.expanded = expanded;
        this.attention = attention;
        this.written = written;
        this.up = up;
        this.down = down;
        this.elapsed = elapsed;
        label = mode == Mode.STARTING ? "等待启动"
            : mode == Mode.RECORDING  ? "正在抓包"
            : mode == Mode.STOPPING   ? "保存中"
            : mode == Mode.CHECKING   ? "检查中"
            : mode == Mode.ERROR      ? "需要检查"
                                      : "工具待命";
        clock = duration(elapsed);
        total = compact(written);
        sent = compact(up);
        received = compact(down);
        description = (mode == Mode.IDLE ? (written > 0 ? "已停止，" : "工具待命，") : label + "，")
            + "主会话已写入 " + written + " 包，发出 " + up + "，收到 " + down + "，时长 " + clock
            + (unknown > 0 ? "，独立保留未知归属 " + unknown + " 包" : "")
            + (attention ? "，详情有需要检查的情况" : "") + "。点击展开工作台，可拖动位置。";
    }
    static BubbleState from(JSONObject j, boolean active, boolean stopping, long nowElapsed) {
        String state = j.optString("state");
        Mode mode = active && state.equals("checking")             ? Mode.CHECKING
            : state.equals("error") || state.equals("interrupted") ? Mode.ERROR
            : stopping                                             ? Mode.STOPPING
            : !active                                              ? Mode.IDLE
            : state.equals("capturing")                            ? Mode.RECORDING
                                                                   : Mode.STARTING;
        // A newly requested capture may briefly coexist with the previous terminal snapshot.
        if (active && !stopping && !state.equals("capturing") && !state.equals("checking")
            && !state.equals("error") && !state.equals("interrupted"))
            mode = Mode.STARTING;
        boolean starting = mode == Mode.STARTING;
        long written = starting ? 0 : Math.max(0, j.optLong("written"));
        long up = starting ? 0 : Math.max(0, j.optLong("up")),
             down = starting ? 0 : Math.max(0, j.optLong("down"));
        long elapsed = 0;
        if (!starting) {
            if (j.has("captureDurationMillis"))
                elapsed = Math.max(0, j.optLong("captureDurationMillis"));
            else if (active && j.has("captureElapsedStart"))
                elapsed = Math.max(0, nowElapsed - Math.max(0, j.optLong("captureElapsedStart")));
        }
        boolean attention = mode == Mode.ERROR || !j.optString("error").isEmpty()
            || state.equals("attention") || state.equals("interrupted")
            || j.optLong("kernelDrops") > 0 || j.optLong("queueDrops") > 0
            || j.optLong("truncated") > 0;
        if (starting)
            attention = false;
        return new BubbleState(mode, active || stopping, attention, written, up, down, elapsed,
            starting ? 0 : Math.max(0, j.optLong("unknownWritten")));
    }
    static String compact(long n) {
        n = Math.max(0, n);
        if (n < 1000)
            return Long.toString(n);
        String[] units = {"K", "M", "B", "T", "P", "E"};
        long scale = 1000;
        int i = 0;
        while (i < units.length - 1 && n / scale >= 1000) {
            scale *= 1000;
            i++;
        }
        long whole = n / scale;
        return whole < 100 ? whole + "." + (n % scale / (scale / 10)) + units[i] : whole + units[i];
    }
    static String duration(long ms) {
        long seconds = Math.max(0, ms) / 1000, hours = seconds / 3600;
        if (hours >= 100)
            return compact(hours) + "h";
        return hours > 0
            ? String.format(Locale.ROOT, "%02d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
            : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
