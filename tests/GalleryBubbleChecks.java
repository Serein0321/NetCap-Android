package io.github.netcap.app;
import java.util.*;
import org.json.JSONObject;
/** Read-only display state / placement regression tests. No Android or Root execution. */
public final class GalleryBubbleChecks {
    static int checks;
    static void need(boolean ok, String why) throws Exception {
        checks++;
        if (!ok)
            throw new Exception(why);
    }
    public static void main(String[] args) throws Exception {
        JSONObject data = new JSONObject()
                              .put("state", "capturing")
                              .put("written", 12345)
                              .put("up", 7000)
                              .put("down", 5000)
                              .put("unknownWritten", 300)
                              .put("captureElapsedStart", 1000);
        String before = data.toString();
        BubbleState s = BubbleState.from(data, true, false, 66000);
        need(s.mode == BubbleState.Mode.RECORDING && s.expanded, "active shape");
        need(s.written == 12345 && s.up == 7000 && s.down == 5000,
            "written count not ingress/unknown");
        need(s.clock.equals("01:05") && s.total.equals("12.3K"), "timer and bounded abbreviation");
        need(s.description.contains("300") && s.description.contains("12345"),
            "exact accessible counters");
        need(data.toString().equals(before), "display does not mutate capture snapshot");
        data.put("captureDurationMillis", 8000).put("state", "checking");
        s = BubbleState.from(data, true, true, 999999);
        need(s.mode == BubbleState.Mode.CHECKING && s.clock.equals("00:08"), "report timer frozen");
        data.put("state", "capturing").remove("captureDurationMillis");
        need(BubbleState.from(data, true, true, 66000).mode == BubbleState.Mode.STOPPING,
            "stopping distinct from recording");
        data.put("state", "saved").put("captureDurationMillis", 8000);
        need(!BubbleState.from(data, false, false, 999999).expanded, "idle collapses");
        s = BubbleState.from(data, true, false, 999999);
        need(s.mode == BubbleState.Mode.STARTING && s.written == 0 && s.elapsed == 0
                && !s.description.contains("300"),
            "new capture never shows previous counts");
        s = BubbleState.from(new JSONObject(), true, false, 999999);
        need(s.written == 0 && s.mode == BubbleState.Mode.STARTING, "fresh initial snapshot");
        data.put("state", "error");
        s = BubbleState.from(data, false, false, 999999);
        need(s.mode == BubbleState.Mode.ERROR && s.attention && !s.expanded,
            "error remains visible in idle bubble");
        data.put("state", "capturing").put("kernelDrops", 1);
        need(BubbleState.from(data, true, false, 999999).attention, "drop attention");
        data.put("kernelDrops", 0).put("queueDrops", 1);
        need(BubbleState.from(data, true, false, 999999).attention, "queue attention");
        data.put("queueDrops", 0).put("truncated", 1);
        need(BubbleState.from(data, true, false, 999999).attention, "truncated attention");
        data.put("truncated", 0)
            .put("written", -8)
            .put("up", -6)
            .put("down", -10)
            .put("captureDurationMillis", -1);
        s = BubbleState.from(data, true, false, 999999);
        need(
            s.written == 0 && s.up == 0 && s.down == 0 && s.elapsed == 0, "negative stats clamped");
        need(BubbleState.compact(0).equals("0"), "zero");
        need(BubbleState.compact(999).equals("999"), "999");
        need(BubbleState.compact(1000).equals("1.0K"), "1K");
        need(BubbleState.compact(9999).equals("9.9K"), "floor instead of inflate");
        need(BubbleState.compact(999999).equals("999K"), "K boundary");
        need(BubbleState.compact(1000000).equals("1.0M"), "1M");
        need(BubbleState.compact(Long.MAX_VALUE).equals("9.2E"), "no overflow");
        need(BubbleState.duration(3599000).equals("59:59"), "minute timer");
        need(BubbleState.duration(3600000).equals("01:00:00"), "hour timer");
        need(BubbleState.duration(100L * 3600000).equals("100h"), "long capture timer");
        need(StyleLayout.columns(344, 1, 1) == 2, "portrait two cards");
        need(StyleLayout.columns(600, 1, 1) == 4, "wide four cards");
        need(StyleLayout.columns(344, 1, 2) == 1, "large font single column");
        need(StyleLayout.columns(0, 1, 1) == 1, "unmeasured grid");
        Set<String> ids = new HashSet<>();
        for (ThemePalette t : ThemePalette.ALL) need(ids.add(t.id), "unique persisted id");
        need(ids.size() == 12 && ids.containsAll(Arrays.asList("indigo", "jade", "ocean", "plum")),
            "legacy themes retained");
        Random random = new Random(20261028);
        for (int i = 0; i < 2000; i++) {
            int w = 400 + random.nextInt(2400), h = 400 + random.nextInt(2400),
                l = random.nextInt(80), r = random.nextInt(80), top = random.nextInt(100),
                bottom = random.nextInt(100);
            int anchorX = random.nextInt(w * 3) - w, anchorY = random.nextInt(h * 3) - h;
            BubbleLayout b =
                BubbleLayout.fit(w, h, l, top, r, bottom, anchorX, anchorY, 52, 148, 58, 4);
            need(b.x >= l && b.y >= top && b.x + b.width <= w - r && b.y + b.height <= h - bottom,
                "capsule safe rectangle " + i);
            int savedX = BubbleLayout.anchorX(b.x, b.width, 52, w, l, r),
                savedY = b.y + (b.height - 52) / 2;
            BubbleLayout same =
                BubbleLayout.fit(w, h, l, top, r, bottom, savedX, savedY, 52, 148, 58, 4);
            need(same.x == b.x && same.y == b.y, "drag release continuity " + i);
            BubbleLayout idle =
                BubbleLayout.fit(w, h, l, top, r, bottom, savedX, savedY, 52, 52, 52, 4);
            BubbleLayout resumed =
                BubbleLayout.fit(w, h, l, top, r, bottom, savedX, savedY, 52, 148, 58, 4);
            need(idle.width == 52 && resumed.x == b.x, "idle/capture anchor " + i);
            int cols = StyleLayout.columns(w, 1 + random.nextFloat() * 3, 1 + random.nextFloat());
            need(cols >= 1 && cols <= 4, "gallery columns bounded " + i);
        }
        BubbleLayout tiny = BubbleLayout.fit(10, 10, 8, 8, 8, 8, 100, 100, 52, 148, 58, 4);
        need(tiny.width > 0 && tiny.height > 0 && tiny.x + tiny.width <= 10
                && tiny.y + tiny.height <= 10,
            "constrained screen never negative");
        System.out.println("PASS " + checks
            + (" gallery/bubble assertions; lifecycle states, exact/compact counters, drag "
               + "geometry and font-aware grid"));
    }
}
