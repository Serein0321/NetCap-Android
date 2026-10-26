package io.github.netcap.app;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** 离线、有界的证据报告。缺口仅指已捕获区间，不推断网络真实丢包或密文协议。 */
final class IntegrityReport {
    static final int MAX_FLOWS = 4096, MAX_RANGES = 200000;
    int ranges, limited, parseErrors;
    long records, truncated, fragmentPackets, kcpPackets, tcpPackets;
    final Map<String, Stream> tcp = new LinkedHashMap<>(), kcp = new LinkedHashMap<>(),
                              fragments = new LinkedHashMap<>();
    final Map<String, Set<Long>> testSequences = new LinkedHashMap<>();
    final List<String> errors = new ArrayList<>();
    void error(String message) {
        if (errors.size() < 30)
            errors.add(message);
    }
    static final class Stream {
        final TreeMap<Long, Long> spans = new TreeMap<>();
        long anchor, packets, duplicate, synSequence = -1, lastSeconds, payloadBytes;
        boolean initialized, syn, fin, uncertain, first;
        long finalEnd = -1;
        long unwrap(long n) {
            if (!initialized) {
                initialized = true;
                anchor = n;
                return n;
            }
            long v = anchor + (int) (n - (anchor & 0xffffffffL));
            if (v > anchor)
                anchor = v;
            return v;
        }
        long gaps() {
            long gaps = 0, end = -1;
            for (Map.Entry<Long, Long> e : spans.entrySet()) {
                if (end >= 0 && e.getKey() > end)
                    gaps += e.getKey() - end;
                end = Math.max(end, e.getValue());
            }
            return gaps;
        }
    }
    void span(Stream s, long start, long end) {
        if (end <= start)
            return;
        if (ranges >= MAX_RANGES) {
            s.uncertain = true;
            limited++;
            return;
        }
        Map.Entry<Long, Long> prev = s.spans.floorEntry(start);
        if (prev != null && prev.getValue() >= start) {
            if (prev.getValue() >= end)
                s.duplicate++;
            start = prev.getKey();
            end = Math.max(end, prev.getValue());
            s.spans.remove(prev.getKey());
            ranges--;
        }
        Map.Entry<Long, Long> next;
        while ((next = s.spans.ceilingEntry(start)) != null && next.getKey() <= end) {
            end = Math.max(end, next.getValue());
            s.spans.remove(next.getKey());
            ranges--;
        }
        s.spans.put(start, end);
        ranges++;
    }
    void fragmentSpan(Stream f, long start, long end) {
        Map.Entry<Long, Long> before = f.spans.floorEntry(start),
                              after = f.spans.ceilingEntry(start);
        if ((before != null && before.getValue() > start)
            || (after != null && after.getKey() < end))
            f.uncertain = true;
        span(f, start, end);
    }
    Stream stream(Map<String, Stream> map, String key) {
        Stream s = map.get(key);
        if (s != null)
            return s;
        if (map.size() >= MAX_FLOWS) {
            limited++;
            return null;
        }
        s = new Stream();
        map.put(key, s);
        return s;
    }
    static long u32(byte[] p, int o, boolean little) {
        long n = 0;
        for (int i = 0; i < 4; i++) n |= (long) (p[o + i] & 255) << (little ? 8 * i : 8 * (3 - i));
        return n;
    }
    void inspect(PacketStore.Record r, byte[] p) {
        records++;
        if (r.caplen < r.wirelen)
            truncated++;
        if ((r.flags & 4) != 0) {
            parseErrors++;
            return;
        }
        PacketStore.Record v = PacketStore.decode(p);
        String route = PacketFilter.endpoint(v.src, v.sp) + " → "
            + PacketFilter.endpoint(v.dst, v.dp) + " /if" + r.iface + " /dir" + r.dir;
        int transport = -1;
        if (v.version == 4 && p.length >= 20) {
            int h = (p[0] & 15) * 4, total = PacketStore.u16(p, 2);
            if (h < 20 || h > p.length || total < h) {
                parseErrors++;
                return;
            }
            int bits = PacketStore.u16(p, 6), off = (bits & 8191) * 8;
            boolean more = (bits & 8192) != 0;
            if (off > 0 || more) {
                fragmentPackets++;
                String key = v.src + " → " + v.dst + " /" + v.proto + " /" + PacketStore.u16(p, 4)
                    + " /if" + r.iface + " /dir" + r.dir;
                Stream f = stream(fragments, key);
                if (f != null) {
                    f.packets++;
                    if ((f.first && off == 0)
                        || (f.lastSeconds > 0 && r.seconds - f.lastSeconds > 30))
                        f.uncertain = true;
                    f.lastSeconds = r.seconds;
                    f.first |= off == 0;
                    int size = Math.min(total, p.length) - h;
                    fragmentSpan(f, off, off + size);
                    if (!more)
                        f.finalEnd = off + size;
                    if (total > p.length)
                        f.uncertain = true;
                }
                return;
            }
            transport = h;
        } else if (v.version == 6 && p.length >= 40) {
            int next = p[6] & 255, off = 40, end = Math.min(p.length, 40 + PacketStore.u16(p, 4));
            for (int i = 0; i < 12; i++) {
                if (next == 44) {
                    if (off + 8 > end) {
                        parseErrors++;
                        return;
                    }
                    int bits = PacketStore.u16(p, off + 2), start = bits & 65528;
                    boolean more = (bits & 1) != 0;
                    if (start > 0 || more) {
                        fragmentPackets++;
                        Stream f = stream(fragments,
                            v.src + " → " + v.dst + " /" + (p[off] & 255) + " /"
                                + u32(p, off + 4, false) + " /if" + r.iface + " /dir" + r.dir);
                        if (f != null) {
                            f.packets++;
                            if ((f.first && start == 0)
                                || (f.lastSeconds > 0 && r.seconds - f.lastSeconds > 30))
                                f.uncertain = true;
                            f.lastSeconds = r.seconds;
                            f.first |= start == 0;
                            fragmentSpan(f, start, start + end - off - 8);
                            if (!more)
                                f.finalEnd = start + end - off - 8;
                        }
                        return;
                    }
                    next = p[off] & 255;
                    off += 8;
                } else if (next == 0 || next == 43 || next == 60 || next == 51) {
                    if (off + 2 > end) {
                        parseErrors++;
                        return;
                    }
                    int h = ((p[off + 1] & 255) + (next == 51 ? 2 : 1)) * (next == 51 ? 4 : 8);
                    if (off + h > end) {
                        parseErrors++;
                        return;
                    }
                    next = p[off] & 255;
                    off += h;
                } else {
                    transport = off;
                    break;
                }
            }
        } else {
            parseErrors++;
            return;
        }
        if (transport < 0 || v.payload > p.length) {
            parseErrors++;
            return;
        }
        if (v.proto == 6) {
            if (transport + 20 > p.length || v.sp == 0) {
                parseErrors++;
                return;
            }
            tcpPackets++;
            Stream t = stream(tcp, route);
            if (t == null)
                return;
            t.packets++;
            t.payloadBytes += v.payloadLen;
            long seq = u32(p, transport + 4, false), start = t.unwrap(seq);
            int flags = p[transport + 13] & 255;
            boolean syn = (flags & 2) != 0, fin = (flags & 1) != 0;
            if (syn) {
                if (t.synSequence >= 0 && t.synSequence != seq)
                    t.uncertain = true;
                t.synSequence = seq;
                t.syn = true;
            }
            t.fin |= fin;
            span(t, start, start + v.payloadLen + (syn ? 1 : 0) + (fin ? 1 : 0));
            if (r.caplen < r.wirelen)
                t.uncertain = true;
        } else if (v.proto == 17) {
            if (transport + 8 > p.length || v.sp == 0
                || PacketStore.u16(p, transport + 4) > p.length - transport
                || PacketStore.u16(p, transport + 4) < 8) {
                parseErrors++;
                return;
            }
            byte[] payload = PacketStore.payload(v, p);
            inspectProbe(route, payload);
            // 标准 KCP 只在整段载荷按 24 字节头+长度严格走完时列为候选，不扫描任意偏移猜协议。
            if (payload.length < 24)
                return;
            int off = 0;
            long conv = u32(payload, 0, true);
            List<Long> sn = new ArrayList<>();
            while (off + 24 <= payload.length) {
                int cmd = payload[off + 4] & 255;
                long len = u32(payload, off + 20, true);
                if (u32(payload, off, true) != conv || cmd < 81 || cmd > 84
                    || len > payload.length - off - 24)
                    return;
                if (cmd == 81)
                    sn.add(u32(payload, off + 12, true));
                off += 24 + (int) len;
            }
            if (off != payload.length || sn.isEmpty())
                return;
            kcpPackets++;
            Stream k = stream(kcp, route + " /conv" + conv);
            if (k == null)
                return;
            k.packets++;
            for (long n : sn) {
                long x = k.unwrap(n);
                span(k, x, x + 1);
            }
        }
    }
    void inspectProbe(String route, byte[] p) {
        if (p.length < 29 || !new String(p, 0, 8, StandardCharsets.US_ASCII).equals("NCAPTEST"))
            return;
        StringBuilder run = new StringBuilder();
        for (int i = 8; i < 24; i++) run.append(String.format(Locale.ROOT, "%02x", p[i] & 255));
        String key = run + " /type" + (p[24] & 255);
        Set<Long> set = testSequences.get(key);
        if (set == null) {
            if (testSequences.size() >= 256) {
                limited++;
                return;
            }
            set = new TreeSet<>();
            testSequences.put(key, set);
        }
        if (set.size() >= 200000) {
            limited++;
            return;
        }
        set.add(u32(p, 25, false));
    }
    void fileCheck(File dir, PacketStore.Cancel cancel) throws IOException {
        File[] all = dir.listFiles((d, n) -> n.matches("capture_\\d+\\.pcap"));
        if (all == null)
            all = new File[0];
        Arrays.sort(all, Comparator.comparing(File::getName));
        long index = 0;
        File idxFile = new File(dir, "packets.idx");
        if (!idxFile.isFile()) {
            error("缺少 packets.idx");
            return;
        }
        if (idxFile.length() % PacketStore.ROW != 0)
            error("索引尾部不足 64 字节");
        try (RandomAccessFile idx = new RandomAccessFile(idxFile, "r")) {
            for (File f : all) {
                if (cancel.isCancelled())
                    throw new IOException("检查已取消");
                int segment = Integer.parseInt(f.getName().substring(8, f.getName().length() - 5));
                try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
                    byte[] h = new byte[24];
                    in.readFully(h);
                    if (u32(h, 0, true) != 0xa1b2c3d4L || u32(h, 20, true) != 101) {
                        error(f.getName() + " 头不符合本工具格式");
                        continue;
                    }
                    while (in.getFilePointer() < in.length()) {
                        if (cancel.isCancelled())
                            throw new IOException("检查已取消");
                        if (in.length() - in.getFilePointer() < 16) {
                            error(f.getName() + " 残缺记录头");
                            break;
                        }
                        byte[] rh = new byte[16];
                        in.readFully(rh);
                        long cap = u32(rh, 8, true), wire = u32(rh, 12, true),
                             offset = in.getFilePointer();
                        if (cap > 262144 || cap > in.length() - offset || cap > wire) {
                            error(f.getName() + " 残缺或非法记录");
                            break;
                        }
                        byte[] raw = new byte[(int) cap];
                        in.readFully(raw);
                        if (index >= idxFile.length() / 64) {
                            error("PCAP 有未索引原包");
                            continue;
                        }
                        PacketStore.Record r = PacketStore.row(idx, dir, index++);
                        if (r.id != index || r.segment != segment || r.offset != offset
                            || r.caplen != cap || r.wirelen != wire)
                            if (errors.size() < 30)
                                error("原包与索引不一致 #" + r.id);
                        inspect(r, raw);
                    }
                } catch (EOFException e) {
                    error(f.getName() + " 文件头残缺");
                }
            }
        }
        if (index != idxFile.length() / 64)
            error("索引引用的原包数量不一致");
    }
    JSONArray streams(Map<String, Stream> map, boolean candidate) throws JSONException {
        JSONArray a = new JSONArray();
        for (Map.Entry<String, Stream> e : map.entrySet()) {
            Stream s = e.getValue();
            long gaps = s.gaps();
            a.put(new JSONObject()
                    .put("connection", e.getKey())
                    .put("packets", s.packets)
                    .put("gapUnits", gaps)
                    .put("duplicateCoveredRanges", s.duplicate)
                    .put("sawSyn", s.syn)
                    .put("sawFin", s.fin)
                    .put("protocol", candidate ? "standard-kcp-candidate" : "tcp")
                    .put("status",
                        s.uncertain || (!candidate && s.payloadBytes == 0) ? "无法判断"
                            : gaps > 0                                     ? "捕获区间存在缺口"
                                                                           : "已观察区间未见缺口"));
        }
        return a;
    }
    static JSONObject generate(File dir, JSONObject manifest, PacketStore.Cancel cancel)
        throws Exception {
        IntegrityReport r = new IntegrityReport();
        r.fileCheck(dir, cancel);
        JSONArray fs = new JSONArray();
        long incomplete = 0;
        for (Map.Entry<String, Stream> e : r.fragments.entrySet()) {
            Stream s = e.getValue();
            boolean complete = s.first && s.finalEnd >= 0 && s.gaps() == 0 && !s.spans.isEmpty()
                && s.spans.firstKey() == 0 && s.spans.lastEntry().getValue() >= s.finalEnd
                && !s.uncertain;
            if (!complete)
                incomplete++;
            fs.put(new JSONObject()
                    .put("datagram", e.getKey())
                    .put("status", complete ? "已观察范围覆盖完整" : "未完成或无法判断")
                    .put("packets", s.packets));
        }
        JSONArray probes = new JSONArray();
        for (Map.Entry<String, Set<Long>> e : r.testSequences.entrySet()) {
            long first = Collections.min(e.getValue()), last = Collections.max(e.getValue());
            probes.put(new JSONObject()
                    .put("stream", e.getKey())
                    .put("uniqueSequences", e.getValue().size())
                    .put("first", first)
                    .put("last", last)
                    .put("internalMissing", last - first + 1 - e.getValue().size()));
        }
        long tcpGaps = 0, tcpUncertain = 0, kcpGaps = 0;
        for (Stream stream : r.tcp.values()) {
            tcpGaps += stream.gaps();
            if (!stream.syn || !stream.fin || stream.uncertain)
                tcpUncertain++;
        }
        for (Stream stream : r.kcp.values()) kcpGaps += stream.gaps();
        JSONObject report =
            new JSONObject()
                .put("schema", 1)
                .put("created", System.currentTimeMillis())
                .put("recordsChecked", r.records)
                .put("fileStructure",
                    r.errors.isEmpty() ? "已确认记录边界与索引一致" : "发现文件结构问题")
                .put("errors", new JSONArray(r.errors))
                .put("truncatedRecords", r.truncated)
                .put("parseFailedSaved", r.parseErrors)
                .put("captureState", manifest.optString("state"))
                .put("captureCounters", manifest)
                .put("tcpGapBytes", tcpGaps)
                .put("tcpBoundaryUnknownFlows", tcpUncertain)
                .put("kcpCandidateMissingSequences", kcpGaps)
                .put("tcp", r.streams(r.tcp, false))
                .put("standardKcpCandidates", r.streams(r.kcp, true))
                .put("fragments", fs)
                .put("incompleteFragmentGroups", incomplete)
                .put("resourceLimitEvents", r.limited)
                .put("testStreams", probes)
                .put("scope",
                    "仅检查已保存数据；首尾未捕获、归属排除、未选接口和资源上限均不能证明无遗漏")
                .put("kcpScope",
                    "仅候选标准明文 "
                        + "KCP；加密、私有头及变体无法判断，序号连续也不能证明整场通信完整")
                .put("fragmentScope",
                    "按地址、标识、协议、接口与方向分组；标识复用可能混淆，未替代完整 IP 重组")
                .put("networkEventsAvailable", new File(dir, "network-events.jsonl").isFile());
        save(dir, "integrity.json", report.toString(2));
        String text = "NetCap 完整性报告\n\n已确认的文件证据\n检查原包：" + r.records + "\n文件："
            + report.getString("fileStructure") + "\n截断：" + r.truncated + "\n保存后解析失败："
            + r.parseErrors + "\n写盘错误："
            + (manifest.optString("error").isEmpty() ? "未报告" : manifest.optString("error"))
            + "\n已入队未写出：" + manifest.optLong("unsavedAccepted")
            + "\n\n已观察区间\nTCP 方向流：" + r.tcp.size() + " · 区间缺口：" + tcpGaps + " 字节"
            + "\nTCP 首尾未覆盖或无法判断：" + tcpUncertain + " 条"
            + "\n标准 KCP 候选：" + r.kcp.size() + " · 区间缺号：" + kcpGaps + "\n未完成分片组："
            + incomplete + "\n\n未覆盖与无法判断\n内核丢弃："
            + (manifest.optBoolean("kernelStatsAvailable") ? manifest.optLong("kernelDrops")
                                                           : "未采样")
            + " · 统计读取失败：" + manifest.optLong("kernelStatsFailures") + "\n队列丢弃："
            + manifest.optLong("queueDrops") + "\n未知归属排除：" + manifest.optLong("unresolved")
            + " · 独立保留：" + manifest.optLong("unknownWritten") + "\n明确过滤："
            + manifest.optLong("filtered") + "\n资源限制事件：" + r.limited + "\n\n"
            + report.getString("scope") + "\n" + report.getString("kcpScope");
        int shown = 0;
        for (Map.Entry<String, Stream> e : r.tcp.entrySet()) {
            if (shown++ >= 100)
                break;
            Stream stream = e.getValue();
            text += "\n\nTCP " + e.getKey() + "\n" + stream.packets + " 包 · 区间缺口 "
                + stream.gaps() + " B"
                + " · SYN " + stream.syn + " · FIN " + stream.fin
                + (stream.uncertain ? " · 无法判断" : "");
        }
        shown = 0;
        for (Map.Entry<String, Stream> e : r.kcp.entrySet()) {
            if (shown++ >= 100)
                break;
            text += "\n\nKCP 候选 " + e.getKey() + "\n区间缺号 " + e.getValue().gaps();
        }
        text += "\n\n此概览最多列出各 100 条流；完整分组、接口事件与依据见 "
            + "integrity.json、network-events.jsonl、attribution.jsonl。";
        save(dir, "integrity.txt", text);
        return report;
    }
    static void save(File dir, String name, String text) throws IOException {
        File tmp = new File(dir, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        PacketStore.replace(tmp, new File(dir, name));
    }
}
