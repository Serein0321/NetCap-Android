package io.github.netcap.app;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.InetAddress;
import java.nio.*;
import java.nio.charset.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;

/** 原始 PCAP 是事实来源，固定长度索引只用于定位；查询不把整个会话装入内存。 */
final class PacketStore {
    static final int ROW = 64;
    static final class Record {
        long id, seconds, offset;
        int micros, segment, caplen, wirelen, iface, payload, payloadLen, uid, sp, dp, proto, dir,
            version, flags;
        File session;
        String src = "", dst = "";
        String protocol() {
            return proto == 6 ? "TCP"
                : proto == 17 ? "UDP"
                : proto == 1  ? "ICMP"
                : proto == 58 ? "ICMPv6"
                              : "IP " + proto;
        }
        String time() {
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
                .format(new Date(seconds * 1000 + micros / 1000));
        }
        String route() {
            return src + (sp > 0 ? ":" + sp : "") + "  →  " + dst + (dp > 0 ? ":" + dp : "");
        }
        String summary() {
            return protocol() + "  ·  "
                + (dir == 1        ? "发出"
                        : dir == 0 ? "收到"
                                   : "方向未知")
                + "  ·  " + caplen + " B";
        }
    }
    static final class Query {
        List<Record> records = new ArrayList<>();
        long next, total, scanned;
        boolean cancelled;
    }
    interface Cancel {
        boolean isCancelled();
    }
    static long count(File session) {
        return new File(session, "packets.idx").length() / ROW;
    }
    static File segment(File session, int n) {
        return new File(session, String.format(Locale.ROOT, "capture_%04d.pcap", n));
    }
    static Record row(RandomAccessFile idx, File session, long n) throws IOException {
        byte[] data = new byte[ROW];
        idx.seek(n * ROW);
        idx.readFully(data);
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        Record r = new Record();
        r.session = session;
        r.id = b.getLong();
        r.seconds = b.getLong();
        r.micros = b.getInt();
        r.segment = b.getInt();
        r.offset = b.getLong();
        r.caplen = b.getInt();
        r.wirelen = b.getInt();
        r.iface = b.getInt();
        r.payload = b.getInt();
        r.payloadLen = b.getInt();
        r.uid = b.getInt();
        r.sp = b.getShort() & 65535;
        r.dp = b.getShort() & 65535;
        r.proto = b.get() & 255;
        r.dir = b.get() & 255;
        r.version = b.get() & 255;
        r.flags = b.get() & 255;
        if (r.caplen < 0 || r.caplen > 262144 || r.offset < 24 || r.segment <= 0)
            throw new IOException("索引记录范围不合法");
        return r;
    }
    static byte[] bytes(Record r) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(segment(r.session, r.segment), "r")) {
            if (r.offset > f.length() || r.caplen > f.length() - r.offset)
                throw new IOException("原始记录尚未刷新，或文件不完整");
            byte[] p = new byte[r.caplen];
            f.seek(r.offset);
            f.readFully(p);
            return p;
        }
    }
    static void addresses(Record r, byte[] p) {
        try {
            if (r.version == 4 && p.length >= 20) {
                r.src = InetAddress.getByAddress(Arrays.copyOfRange(p, 12, 16)).getHostAddress();
                r.dst = InetAddress.getByAddress(Arrays.copyOfRange(p, 16, 20)).getHostAddress();
            } else if (r.version == 6 && p.length >= 40) {
                r.src = InetAddress.getByAddress(Arrays.copyOfRange(p, 8, 24)).getHostAddress();
                r.dst = InetAddress.getByAddress(Arrays.copyOfRange(p, 24, 40)).getHostAddress();
            }
        } catch (Exception ignored) {
            r.src = "未知";
            r.dst = "未知";
        }
    }
    static byte[] payload(Record r, byte[] p) {
        int start = Math.max(0, Math.min(r.payload, p.length)),
            len = Math.max(0, Math.min(r.payloadLen, p.length - start));
        return Arrays.copyOfRange(p, start, start + len);
    }
    static Query query(File session, long start, boolean newest, String text, int protocol,
        Cancel cancel) throws IOException {
        return query(session, start, newest, text, protocol, cancel, 0);
    }
    static Query query(File session, long start, boolean newest, String text, int protocol,
        Cancel cancel, long afterSequence) throws IOException {
        Query q = new Query();
        q.total = count(session);
        long pos = start < 0 ? (newest ? q.total - 1 : 0) : start;
        String term = text == null ? "" : text.trim();
        byte[] needle = null;
        if (term.toLowerCase(Locale.ROOT).startsWith("hex:")) {
            String h = term.substring(4).replaceAll("\\s+", "");
            if (h.isEmpty() || h.length() % 2 != 0 || !h.matches("[0-9a-fA-F]*"))
                throw new IOException("hex: 后请输入成对的十六进制字节");
            needle = new byte[h.length() / 2];
            for (int i = 0; i < needle.length; i++)
                needle[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        try (RandomAccessFile idx = new RandomAccessFile(new File(session, "packets.idx"), "r")) {
            for (; pos >= 0 && pos < q.total; pos += newest ? -1 : 1) {
                if (cancel.isCancelled()) {
                    q.cancelled = true;
                    break;
                }
                q.scanned++;
                Record r = row(idx, session, pos);
                if (r.id <= afterSequence) {
                    if (newest) {
                        pos = -1;
                        break;
                    } else
                        continue;
                }
                if (protocol != 0 && r.proto != protocol)
                    continue;
                byte[] raw;
                try {
                    raw = bytes(r);
                } catch (EOFException e) {
                    continue;
                } catch (IOException e) {
                    if (pos == q.total - 1)
                        continue;
                    throw e;
                }
                addresses(r, raw);
                boolean match = term.isEmpty();
                if (needle != null)
                    match = contains(raw, needle);
                else if (!match)
                    match = (r.route() + " " + r.protocol() + " "
                        + new String(raw, StandardCharsets.UTF_8))
                                .toLowerCase(Locale.ROOT)
                                .contains(term.toLowerCase(Locale.ROOT));
                if (match) {
                    q.records.add(r);
                    if (q.records.size() >= 80) {
                        pos += newest ? -1 : 1;
                        break;
                    }
                }
            }
        }
        q.next = pos;
        return q;
    }
    static boolean contains(byte[] data, byte[] needle) {
    outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++)
                if (data[i + j] != needle[j])
                    continue outer;
            return true;
        }
        return false;
    }
    static String hex(byte[] p) {
        StringBuilder s = new StringBuilder(p.length * 5);
        for (int o = 0; o < p.length; o += 16) {
            s.append(String.format(Locale.ROOT, "%06X  ", o));
            for (int j = 0; j < 16; j++)
                s.append(
                    o + j < p.length ? String.format(Locale.ROOT, "%02X ", p[o + j] & 255) : "   ");
            s.append(" ");
            for (int j = 0; j < 16 && o + j < p.length; j++) {
                int c = p[o + j] & 255;
                s.append(c >= 32 && c < 127 ? (char) c : '.');
            }
            s.append('\n');
        }
        return s.toString();
    }
    static String readable(
        byte[] p) { // 为什么：只对确实能解码的文本格式化，二进制和密文不伪装成已解析。
        if (p.length == 0)
            return "此记录没有可展示的载荷。";
        try {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                                     .onMalformedInput(CodingErrorAction.REPORT)
                                     .onUnmappableCharacter(CodingErrorAction.REPORT);
            String s = dec.decode(ByteBuffer.wrap(p)).toString();
            int bad = 0;
            for (int i = 0; i < s.length(); i++)
                if (s.charAt(i) < 32 && "\n\r\t".indexOf(s.charAt(i)) < 0)
                    bad++;
            if (bad > Math.max(1, s.length() / 20))
                return "二进制载荷。可通过原始字节、包间差异和通信时间线观察；尚无可验证的业务协议"
                    + "定义。";
            String t = s.trim();
            try {
                if (t.length() <= 262144 && safeJsonDepth(t) && t.startsWith("{"))
                    return new JSONObject(t).toString(2);
                if (t.length() <= 262144 && safeJsonDepth(t) && t.startsWith("["))
                    return new JSONArray(t).toString(2);
            } catch (JSONException ignored) {
            }
            return "UTF-8 文本\n\n" + s;
        } catch (CharacterCodingException e) {
            if (p.length > 5 && (p[0] & 255) >= 20 && (p[0] & 255) <= 23 && p[1] == 3)
                return "疑似 TLS 记录，载荷可能加密。此提示不代表已解密或识别游戏协议。";
            return "未识别为完整 UTF-8 文本。可查看原始字节、标记位置，或与其他记录比较。";
        }
    }
    // 为什么：网络载荷可以任意构造，嵌套过深的 JSON 只显示文本，不递归解析。
    private static boolean safeJsonDepth(String s) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (escaped)
                    escaped = false;
                else if (c == '\\')
                    escaped = true;
                else if (c == '"')
                    quoted = false;
            } else if (c == '"')
                quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > 48)
                    return false;
            } else if (c == '}' || c == ']')
                depth--;
        }
        return true;
    }
    static String diff(byte[] a, byte[] b) {
        StringBuilder s =
            new StringBuilder("载荷 A：" + a.length + " B   载荷 B：" + b.length + " B\n\n");
        int changed = 0, shown = 0;
        for (int i = 0; i < Math.max(a.length, b.length); i++)
            if (i >= a.length || i >= b.length || a[i] != b[i]) {
                changed++;
                if (shown++ < 512)
                    s.append(String.format(Locale.ROOT, "%06X   %s  →  %s\n", i,
                        i < a.length ? String.format(Locale.ROOT, "%02X", a[i] & 255) : "—",
                        i < b.length ? String.format(Locale.ROOT, "%02X", b[i] & 255) : "—"));
            }
        return "不同位置：" + changed + "\n"
            + (changed > 512 ? "下面展示前 512 个差异位置。\n" : "") + s;
    }
    static List<String> flows(File session, Cancel cancel) throws IOException {
        Map<String, long[]> map = new LinkedHashMap<>();
        long other = 0;
        try (RandomAccessFile idx = new RandomAccessFile(new File(session, "packets.idx"), "r")) {
            long n = count(session);
            for (long i = 0; i < n; i++) {
                if (cancel.isCancelled())
                    break;
                Record r = row(idx, session, i);
                byte[] raw = bytes(r);
                addresses(r, raw);
                String a = r.src + ":" + r.sp, b = r.dst + ":" + r.dp;
                String key =
                    r.protocol() + " · " + (a.compareTo(b) < 0 ? a + " ↔ " + b : b + " ↔ " + a);
                long[] v = map.get(key);
                if (v == null) {
                    if (map.size() >= 2000) {
                        other++;
                        continue;
                    }
                    v = new long[3];
                    map.put(key, v);
                }
                v[0]++;
                v[1] += r.caplen;
                v[2] += r.dir == 1 ? 1 : 0;
            }
        }
        List<Map.Entry<String, long[]>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : entries)
            out.add(e.getKey() + "\n" + e.getValue()[0] + " 包 · " + formatBytes(e.getValue()[1])
                + " · 发出 " + e.getValue()[2]);
        if (other > 0)
            out.add(
                "其他连接记录 " + other + " 包（连接概览最多汇总 2000 条，原始数据仍完整保留）");
        return out;
    }
    static JSONObject manifest(File session) {
        try {
            return new JSONObject(readText(new File(session, "session.json")));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    static synchronized void saveManifest(File session, JSONObject obj) throws IOException {
        File tmp = new File(session, "session.json.tmp");
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            f.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            f.getFD().sync();
        }
        if (!tmp.renameTo(new File(session, "session.json")))
            throw new IOException("保存会话状态失败");
    }
    static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f);
            ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] a = new byte[8192];
            int n;
            while ((n = in.read(a)) >= 0) {
                if (b.size() > 4 * 1024 * 1024)
                    throw new IOException("元数据文件过大");
                b.write(a, 0, n);
            }
            return b.toString("UTF-8");
        }
    }
    static String formatBytes(long n) {
        return n < 1024   ? n + " B"
            : n < 1048576 ? String.format(Locale.ROOT, "%.1f KB", n / 1024.0)
                          : String.format(Locale.ROOT, "%.2f MB", n / 1048576.0);
    }
    static List<File> history(Context c, File configured) {
        Set<String> seen = new HashSet<>();
        List<File> result = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        roots.add(new File(c.getExternalFilesDir(null), "captures"));
        roots.add(configured);
        for (String path :
            c.getSharedPreferences("settings", 0).getStringSet("roots", Collections.emptySet()))
            roots.add(new File(path));
        for (File root : roots) {
            if (root == null)
                continue;
            File[] dirs = root.listFiles();
            if (dirs == null)
                continue;
            for (File d : dirs)
                if (d.isDirectory() && new File(d, "session.json").exists()
                    && seen.add(d.getAbsolutePath()))
                    result.add(d);
        }
        result.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return result;
    }
    static void export(File session, OutputStream out, String type)
        throws IOException { // 为什么：导出从磁盘流式读取，不把长会话或大文件整体放进内存。
        if (type.equals("zip")) {
            ZipOutputStream z = new ZipOutputStream(out);
            File[] files = session.listFiles();
            if (files != null)
                for (File f : files)
                    if (f.isFile() && !f.getName().endsWith(".tmp")) {
                        z.putNextEntry(new ZipEntry(f.getName()));
                        try (InputStream in = new FileInputStream(f)) {
                            copy(in, z);
                        }
                        z.closeEntry();
                    }
            z.finish();
            return;
        }
        if (type.equals("pcap")) {
            List<File> segments = new ArrayList<>();
            File[] files = session.listFiles();
            if (files != null)
                for (File f : files)
                    if (f.getName().matches("capture_\\d+\\.pcap"))
                        segments.add(f);
            segments.sort(Comparator.comparing(File::getName));
            boolean first = true;
            for (File f : segments)
                try (InputStream in = new FileInputStream(f)) {
                    byte[] header = new byte[24];
                    if (in.read(header) != 24)
                        throw new IOException("PCAP 文件头不完整");
                    if (first) {
                        out.write(header);
                        first = false;
                    }
                    copy(in, out);
                }
            return;
        }
        try (RandomAccessFile idx = new RandomAccessFile(new File(session, "packets.idx"), "r")) {
            long n = count(session);
            for (long i = 0; i < n; i++) {
                Record r = row(idx, session, i);
                byte[] raw = bytes(r);
                addresses(r, raw);
                String text;
                if (type.equals("jsonl")) {
                    JSONObject j = new JSONObject();
                    try {
                        j.put("sequence", r.id)
                            .put("seconds", r.seconds)
                            .put("microseconds", r.micros)
                            .put("source", r.src)
                            .put("destination", r.dst)
                            .put("sourcePort", r.sp)
                            .put("destinationPort", r.dp)
                            .put("protocol", r.proto)
                            .put("interface", r.iface)
                            .put("direction",
                                r.dir == 1       ? "out"
                                    : r.dir == 0 ? "in"
                                                 : "unknown")
                            .put("length", r.caplen)
                            .put("payloadHex", compact(payload(r, raw)));
                    } catch (JSONException e) {
                        throw new IOException(e);
                    }
                    text = j + "\n";
                } else
                    text = "#" + r.id + " " + r.time() + " " + r.summary() + "\n" + r.route() + "\n"
                        + hex(raw) + "\n";
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        }
    }
    static String compact(byte[] p) {
        final char[] digits = "0123456789ABCDEF".toCharArray();
        char[] out = new char[p.length * 2];
        for (int i = 0; i < p.length; i++) {
            int v = p[i] & 255;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 15];
        }
        return new String(out);
    }
    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1)
            out.write(b, 0, n);
    }
    static void writeRow(OutputStream out, Record r) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(r.id)
            .putLong(r.seconds)
            .putInt(r.micros)
            .putInt(r.segment)
            .putLong(r.offset)
            .putInt(r.caplen)
            .putInt(r.wirelen)
            .putInt(r.iface)
            .putInt(r.payload)
            .putInt(r.payloadLen)
            .putInt(r.uid)
            .putShort((short) r.sp)
            .putShort((short) r.dp)
            .put((byte) r.proto)
            .put((byte) r.dir)
            .put((byte) r.version)
            .put((byte) r.flags);
        out.write(b.array());
    }
    static Record decode(byte[] raw) {
        Record r = new Record();
        if (raw.length < 1)
            return r;
        r.version = (raw[0] >> 4) & 15;
        int off = 0, end = raw.length;
        if (r.version == 4 && raw.length >= 20) {
            off = (raw[0] & 15) * 4;
            r.proto = raw[9] & 255;
            int total = u16(raw, 2);
            if (total >= off)
                end = Math.min(end, total);
            r.flags = (u16(raw, 6) & 0x3fff) != 0 ? 1 : 0;
        } else if (r.version == 6 && raw.length >= 40) {
            off = 40;
            r.proto = raw[6] & 255;
            int len = u16(raw, 4);
            if (len > 0)
                end = Math.min(end, 40 + len);
            for (int i = 0; i < 12; i++) {
                if (r.proto == 44) {
                    if (off + 8 > end)
                        break;
                    r.flags = (u16(raw, off + 2) & 0xfff9) != 0 ? 1 : 0;
                    r.proto = raw[off] & 255;
                    off += 8;
                    if (r.flags != 0)
                        break;
                } else if (r.proto == 0 || r.proto == 43 || r.proto == 60 || r.proto == 51) {
                    if (off + 2 > end)
                        break;
                    int h =
                        ((raw[off + 1] & 255) + (r.proto == 51 ? 2 : 1)) * (r.proto == 51 ? 4 : 8);
                    if (off + h > end)
                        break;
                    r.proto = raw[off] & 255;
                    off += h;
                } else
                    break;
            }
        } else
            return r;
        if (off < 0 || off > end) {
            off = end;
        }
        r.payload = off;
        r.payloadLen = end - off;
        if (r.flags == 0 && r.proto == 6 && off + 20 <= end) {
            int h = ((raw[off + 12] & 255) >> 4) * 4;
            if (h >= 20 && off + h <= end) {
                r.sp = u16(raw, off);
                r.dp = u16(raw, off + 2);
                r.payload = off + h;
                r.payloadLen = end - r.payload;
            }
        } else if (r.flags == 0 && r.proto == 17 && off + 8 <= end) {
            int len = u16(raw, off + 4);
            if (len >= 8) {
                r.sp = u16(raw, off);
                r.dp = u16(raw, off + 2);
                r.payload = off + 8;
                r.payloadLen = Math.min(end - off, len) - 8;
            }
        }
        addresses(r, raw);
        return r;
    }
    static int u16(byte[] b, int off) {
        return ((b[off] & 255) << 8) | (b[off + 1] & 255);
    }
    static void importPcap(InputStream source, File dir, JSONObject manifest) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(source));
            OutputStream pcap = new BufferedOutputStream(new FileOutputStream(segment(dir, 1)));
            OutputStream idx =
                new BufferedOutputStream(new FileOutputStream(new File(dir, "packets.idx")))) {
            byte[] h = new byte[24];
            in.readFully(h);
            int magic = ByteBuffer.wrap(h).getInt();
            boolean little = magic == 0xd4c3b2a1 || magic == 0x4d3cb2a1;
            boolean nanos = magic == 0x4d3cb2a1 || magic == 0xa1b23c4d;
            if (!little && magic != 0xa1b2c3d4 && magic != 0xa1b23c4d)
                throw new IOException("当前导入支持 PCAP；PCAPNG 请先在电脑转换为 PCAP");
            ByteOrder order = little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            int link = ByteBuffer.wrap(h).order(order).getInt(20);
            if (link != 101 && link != 1 && link != 113 && link != 228 && link != 229)
                throw new IOException("不支持此 PCAP 链路类型: " + link);
            ByteBuffer outhead = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            outhead.putInt(0xa1b2c3d4)
                .putShort((short) 2)
                .putShort((short) 4)
                .putInt(0)
                .putInt(0)
                .putInt(262144)
                .putInt(101);
            pcap.write(outhead.array());
            long offset = 24, id = 0, skipped = 0;
            while (true) {
                int first = in.read();
                if (first == -1)
                    break;
                byte[] rh = new byte[16];
                rh[0] = (byte) first;
                in.readFully(rh, 1, 15);
                ByteBuffer b = ByteBuffer.wrap(rh).order(order);
                long sec = b.getInt() & 0xffffffffL;
                int us = b.getInt();
                int cap = b.getInt(), wire = b.getInt();
                if (cap < 0 || cap > 262144)
                    throw new IOException("PCAP 单条记录超过本版支持的 256 KiB，或长度不合法");
                byte[] packet = new byte[cap];
                in.readFully(packet);
                int skip = 0;
                if (link == 1) {
                    if (cap < 14) {
                        skipped++;
                        continue;
                    }
                    int eth = u16(packet, 12);
                    skip = 14;
                    while ((eth == 0x8100 || eth == 0x88a8) && skip + 4 <= cap) {
                        eth = u16(packet, skip + 2);
                        skip += 4;
                    }
                    if (eth != 0x800 && eth != 0x86dd) {
                        skipped++;
                        continue;
                    }
                } else if (link == 113) {
                    if (cap < 16 || (u16(packet, 14) != 0x800 && u16(packet, 14) != 0x86dd)) {
                        skipped++;
                        continue;
                    }
                    skip = 16;
                }
                byte[] raw = Arrays.copyOfRange(packet, skip, packet.length);
                Record r = decode(raw);
                if (r.version != 4 && r.version != 6) {
                    skipped++;
                    continue;
                }
                r.id = ++id;
                r.seconds = sec;
                r.micros = nanos ? us / 1000 : us;
                r.segment = 1;
                r.offset = offset + 16;
                r.caplen = raw.length;
                r.wirelen = Math.max(raw.length, wire - skip);
                r.uid = -1;
                r.dir = 2;
                ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                header.putInt((int) sec).putInt(r.micros).putInt(r.caplen).putInt(r.wirelen);
                pcap.write(header.array());
                pcap.write(raw);
                writeRow(idx, r);
                offset += 16 + raw.length;
            }
            try {
                manifest.put("written", id)
                    .put("importSkippedNonIP", skipped)
                    .put("state", "imported");
            } catch (JSONException e) {
                throw new IOException(e);
            }
        }
        saveManifest(dir, manifest);
    }
    static File demo(Context c) throws Exception {
        File base = new File(c.getExternalFilesDir(null), "captures");
        base.mkdirs();
        File dir = new File(base, "演示_" + System.currentTimeMillis());
        if (!dir.mkdir())
            throw new IOException("创建演示目录失败");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0xa1b2c3d4)
            .putShort((short) 2)
            .putShort((short) 4)
            .putInt(0)
            .putInt(0)
            .putInt(262144)
            .putInt(101);
        raw.write(h.array());
        for (int i = 0; i < 24; i++) {
            byte[] payload =
                ("{\"event\":\"sample\",\"sequence\":" + i + ",\"value\":" + (100 + i * 3) + "}")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] p = new byte[28 + payload.length];
            p[0] = 0x45;
            p[2] = (byte) (p.length >> 8);
            p[3] = (byte) p.length;
            p[8] = 64;
            p[9] = 17;
            byte[] src = {(byte) 192, 0, 2, 10}, dst = {(byte) 198, 51, 100, 20};
            System.arraycopy(i % 2 == 0 ? src : dst, 0, p, 12, 4);
            System.arraycopy(i % 2 == 0 ? dst : src, 0, p, 16, 4);
            p[20] = 0x61;
            p[21] = (byte) 0xa8;
            p[22] = 0x23;
            p[23] = 0x28;
            p[24] = (byte) ((payload.length + 8) >> 8);
            p[25] = (byte) (payload.length + 8);
            System.arraycopy(payload, 0, p, 28, payload.length);
            ByteBuffer rh = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            rh.putInt(1700000000 + i).putInt(0).putInt(p.length).putInt(p.length);
            raw.write(rh.array());
            raw.write(p);
        }
        JSONObject m = new JSONObject()
                           .put("label", "演示会话 · 合成数据")
                           .put("scope", "demo")
                           .put("started", System.currentTimeMillis());
        importPcap(new ByteArrayInputStream(raw.toByteArray()), dir, m);
        return dir;
    }
}
