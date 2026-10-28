package io.github.netcap.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.*;
import java.util.*;

/** Read-only, bounded TCP prefix reassembly for visible TLS handshakes. Never decrypts. */
final class TlsInspector {
    static final int MAX_ROWS = 250000, MAX_SEGMENTS = 8192, MAX_BYTES = 4 * 1024 * 1024,
                     MAX_DIRECTION = 2 * 1024 * 1024, MAX_HANDSHAKE = 262144;
    static final class Segment {
        long seq, time, id;
        int side, flags;
        byte[] bytes;
        Segment(int side, long seq, byte[] bytes, long time, int flags, long id) {
            this.side = side;
            this.seq = seq;
            this.bytes = bytes;
            this.time = time;
            this.flags = flags;
            this.id = id;
        }
    }
    static final class Hello {
        int side, version, cipher;
        boolean client, retry, ech;
        long time;
        String sni = "", alpn = "", versions = "", certificate = "";
    }
    static final class Direction {
        byte[] bytes = new byte[0];
        final TreeMap<Integer, Long> times = new TreeMap<>();
        long timeAt(int offset) {
            Map.Entry<Integer, Long> e = times.floorEntry(offset);
            return e == null ? 0 : e.getValue();
        }
        int records, encrypted, duplicate, alerts, consumed;
        boolean gap, conflict, limited, partial, framed, malformed, changedCipher, syn;
        final List<Hello> hellos = new ArrayList<>();
        final List<String> alertLabels = new ArrayList<>();
        String certificate = "";
    }
    static final class Result {
        final Direction[] directions = {new Direction(), new Direction()};
        final List<String> warnings = new ArrayList<>();
        String route = "", status = "", version = "未确认", sni = "未观察到",
               offeredAlpn = "未观察到", selectedAlpn = "未观察到", cipher = "未确认",
               offeredVersions = "未观察到", certificate = "未观察到明文证书";
        long scanned, total, packets, payloadBytes, firstTime = Long.MAX_VALUE, lastTime,
                                                    helloMicros = -1;
        int skipped, truncated, clientSide = -1, retries;
        boolean limited, cancelled, foundAnchor, ech;
        String summary() {
            return clientSide >= 0                             ? "已识别 TLS 握手"
                : directions[0].framed || directions[1].framed ? "疑似 TLS 记录"
                                                               : "未确认 TLS";
        }
        String coverage() {
            return "扫描 " + scanned + " / " + total + " 条 · 纳入前缀 " + packets + " 包 · "
                + PacketStore.formatBytes(payloadBytes) + " 载荷";
        }
    }
    static int u16(byte[] b, int p) {
        return ((b[p] & 255) << 8) | (b[p + 1] & 255);
    }
    static long u32(byte[] b, int p) {
        return ((long) (b[p] & 255) << 24) | ((long) (b[p + 1] & 255) << 16)
            | ((long) (b[p + 2] & 255) << 8) | (b[p + 3] & 255);
    }
    static int transport(byte[] raw) {
        if (raw.length < 20)
            return -1;
        int v = (raw[0] & 255) >>> 4, off, proto, end;
        if (v == 4) {
            off = (raw[0] & 15) * 4;
            end = u16(raw, 2);
            proto = raw[9] & 255;
            if (off < 20 || end < off || (u16(raw, 6) & 0x3fff) != 0)
                return -1;
        } else if (v == 6 && raw.length >= 40) {
            off = 40;
            end = 40 + u16(raw, 4);
            proto = raw[6] & 255;
            for (int i = 0; i < 12 && proto != 6; i++) {
                if (proto == 44 || off + 2 > raw.length || off + 2 > end)
                    return -1;
                if (proto != 0 && proto != 43 && proto != 60 && proto != 51)
                    return -1;
                int len = (raw[off + 1] & 255) + (proto == 51 ? 2 : 1);
                len *= proto == 51 ? 4 : 8;
                if (len < 8 || off + len > raw.length || off + len > end)
                    return -1;
                proto = raw[off] & 255;
                off += len;
            }
        } else
            return -1;
        if (proto != 6 || off + 20 > raw.length || off + 20 > end)
            return -1;
        int tcp = (raw[off + 12] & 255) >>> 4;
        return tcp >= 5 && off + tcp * 4 <= raw.length && off + tcp * 4 <= end ? off : -1;
    }
    static Result inspect(PacketStore.Record anchor, PacketStore.Cancel cancel) throws IOException {
        Result out = new Result();
        out.route = anchor.route();
        if (anchor.proto != 6) {
            out.status = "此入口分析 TCP 上的 TLS；UDP/QUIC/DTLS 不在本版解析范围。";
            return out;
        }
        out.total = PacketStore.count(anchor.session);
        List<Segment> segments = new ArrayList<>();
        int budget = 0;
        try (RandomAccessFile idx =
                 new RandomAccessFile(new File(anchor.session, "packets.idx"), "r");
            HeaderReader headers = new HeaderReader(anchor.session);
            PacketStore.RawReader reader = new PacketStore.RawReader(anchor.session)) {
            // Verify consecutive id/offset before using the id as a row locator.
            long pos = Math.min(Math.max(0, anchor.id - 1), Math.max(0, out.total - 1));
            PacketStore.Record probe = PacketStore.row(idx, anchor.session, pos);
            if (probe.id != anchor.id || probe.offset != anchor.offset
                || probe.segment != anchor.segment)
                throw new IOException("记录定位发生变化，请重新打开详情");
            long begin = Math.max(0, pos - MAX_ROWS / 2),
                 end = Math.min(out.total, begin + MAX_ROWS);
            out.limited = begin > 0 || end < out.total;
            long lo = begin, hi = end, syn = -1;
            int synSide = -1;
            boolean ended = false;
            // Locate the selected connection epoch with headers first. A large earlier connection
            // must not exhaust the payload budget before we reach the chosen packet.
            for (long n = begin; n < end; n++) {
                if (cancel.isCancelled()) {
                    out.cancelled = true;
                    return out;
                }
                out.scanned++;
                PacketStore.Record r = PacketStore.row(idx, anchor.session, n);
                if (!candidate(r, anchor))
                    continue;
                byte[] raw;
                try {
                    raw = headers.read(r);
                } catch (IOException e) {
                    out.skipped++;
                    continue;
                }
                int side = side(r, raw, anchor), tcp = transport(raw);
                if (side < 0)
                    continue;
                if (tcp < 0) {
                    out.skipped++;
                    continue;
                }
                int flags = raw[tcp + 13] & 255;
                long seq = u32(raw, tcp + 4);
                if (ended) {
                    if (out.foundAnchor) {
                        hi = n;
                        break;
                    }
                    lo = n;
                    syn = -1;
                    ended = false;
                }
                if ((flags & 0x12) == 2 && (syn < 0 || syn != seq || synSide != side)) {
                    if (out.foundAnchor) {
                        hi = n;
                        break;
                    }
                    lo = n;
                    syn = seq;
                    synSide = side;
                }
                if (r.id == anchor.id)
                    out.foundAnchor = true;
                if ((flags & 4) != 0)
                    ended = true;
            }
            if (!out.foundAnchor) {
                out.status = "扫描范围未覆盖可安全解析的所选记录；未输出连接结论。";
                return out;
            }
            for (long n = lo; n < hi; n++) {
                if (cancel.isCancelled()) {
                    out.cancelled = true;
                    return out;
                }
                PacketStore.Record r = PacketStore.row(idx, anchor.session, n);
                if (!candidate(r, anchor))
                    continue;
                byte[] head;
                try {
                    head = headers.read(r);
                } catch (IOException e) {
                    continue;
                }
                if (side(r, head, anchor) < 0 || transport(head) < 0)
                    continue;
                byte[] raw;
                try {
                    raw = reader.read(r);
                } catch (IOException e) {
                    out.skipped++;
                    continue;
                }
                int side = side(r, raw, anchor), tcp = transport(raw);
                if (side < 0 || tcp < 0) {
                    out.skipped++;
                    continue;
                }
                int ipEnd = (raw[0] >>> 4 & 15) == 4 ? u16(raw, 2) : 40 + u16(raw, 4);
                int start = tcp + ((raw[tcp + 12] & 255) >>> 4) * 4,
                    length = Math.min(raw.length, ipEnd) - start;
                if (r.caplen < r.wirelen || ipEnd > raw.length)
                    out.truncated++;
                if (segments.size() >= MAX_SEGMENTS || budget + length > MAX_BYTES) {
                    out.limited = true;
                    break;
                }
                Segment seg = new Segment(side, u32(raw, tcp + 4),
                    Arrays.copyOfRange(raw, start, start + length), r.seconds * 1000000 + r.micros,
                    raw[tcp + 13] & 255, r.id);
                segments.add(seg);
                budget += length;
            }
        }
        List<Segment> epoch = segments;
        for (Segment s : epoch) {
            out.packets++;
            out.payloadBytes += s.bytes.length;
            out.firstTime = Math.min(out.firstTime, s.time);
            out.lastTime = Math.max(out.lastTime, s.time);
        }
        for (int side = 0; side < 2; side++) {
            Direction dir = reassemble(epoch, side);
            out.directions[side] = dir;
            parse(dir, side);
        }
        finish(out);
        return out;
    }
    static boolean candidate(PacketStore.Record r, PacketStore.Record anchor) {
        return r.proto == 6 && r.iface == anchor.iface
            && ((r.sp == anchor.sp && r.dp == anchor.dp)
                || (r.sp == anchor.dp && r.dp == anchor.sp));
    }
    static int side(PacketStore.Record r, byte[] header, PacketStore.Record anchor) {
        PacketStore.addresses(r, header);
        return r.src.equals(anchor.src) && r.dst.equals(anchor.dst) && r.sp == anchor.sp
                && r.dp == anchor.dp
            ? 0
            : r.src.equals(anchor.dst) && r.dst.equals(anchor.src) && r.sp == anchor.dp
                && r.dp == anchor.sp
            ? 1
            : -1;
    }
    static final class HeaderReader implements AutoCloseable {
        final File session;
        RandomAccessFile file;
        int segment = -1;
        HeaderReader(File session) {
            this.session = session;
        }
        byte[] read(PacketStore.Record r) throws IOException {
            if (file == null || segment != r.segment) {
                close();
                file = new RandomAccessFile(PacketStore.segment(session, r.segment), "r");
                segment = r.segment;
            }
            if (r.offset > file.length() || r.caplen > file.length() - r.offset)
                throw new IOException("short record");
            byte[] b = new byte[Math.min(4096, r.caplen)];
            file.seek(r.offset);
            file.readFully(b);
            return b;
        }
        public void close() throws IOException {
            if (file != null) {
                file.close();
                file = null;
            }
        }
    }
    static Direction reassemble(List<Segment> source, int side) {
        Direction out = new Direction();
        List<Segment> segs = new ArrayList<>();
        long origin = -1;
        for (Segment s : source)
            if (s.side == side) {
                if ((s.flags & 2) != 0) {
                    out.syn = true;
                    if (origin < 0)
                        origin = (s.seq + 1) & 0xffffffffL;
                }
                if (s.bytes.length > 0)
                    segs.add(s);
            }
        if (segs.isEmpty())
            return out;
        if (origin < 0)
            origin = segs.get(0).seq;
        final long base = origin;
        segs.sort(Comparator.comparingLong(
            s -> (int) ((s.seq + ((s.flags & 2) != 0 ? 1 : 0) - base) & 0xffffffffL)));
        // Store time ranges, not one timestamp for each byte.
        long capacity = 0;
        for (Segment s : segs) capacity += s.bytes.length;
        byte[] buffer = new byte[(int) Math.min(MAX_DIRECTION, capacity)];
        int size = 0;
        long start = out.syn
            ? 0
            : (int) ((segs.get(0).seq + ((segs.get(0).flags & 2) != 0 ? 1 : 0) - base)
                  & 0xffffffffL);
        for (Segment s : segs) {
            long off = (int) ((s.seq + ((s.flags & 2) != 0 ? 1 : 0) - base) & 0xffffffffL) - start;
            if (off < 0) {
                out.conflict = true;
                break;
            }
            if (off > size) {
                out.gap = true;
                break;
            }
            int p = (int) off, overlap = Math.min(s.bytes.length, size - p);
            for (int i = 0; i < overlap; i++)
                if (buffer[p + i] != s.bytes[i]) {
                    out.conflict = true;
                    break;
                }
            if (out.conflict)
                break;
            if (overlap > 0)
                out.duplicate++;
            int fresh = s.bytes.length - overlap;
            if (size + fresh > MAX_DIRECTION) {
                out.limited = true;
                break;
            }
            System.arraycopy(s.bytes, overlap, buffer, size, fresh);
            if (fresh > 0)
                out.times.put(size, s.time);
            size += fresh;
        }
        if (!out.conflict) {
            out.bytes = Arrays.copyOf(buffer, size);
        }
        return out;
    }
    static void parse(Direction out, int side) {
        byte[] b = out.bytes;
        int p = 0;
        ByteArrayOutputStream hs = new ByteArrayOutputStream();
        long hsTime = 0;
        boolean opaque = false;
        while (p + 5 <= b.length) {
            int type = b[p] & 255, version = u16(b, p + 1), len = u16(b, p + 3);
            if (type < 20 || type > 24 || version < 0x0300 || version > 0x0303 || len > 18432) {
                out.malformed = true;
                break;
            }
            if (p + 5 + len > b.length) {
                out.partial = true;
                break;
            }
            out.framed = true;
            out.records++;
            if (type == 23) {
                out.encrypted++;
                opaque = true;
            }
            if (type == 20) {
                if (len != 1 || b[p + 5] != 1) {
                    out.malformed = true;
                    break;
                }
                boolean tls13 = false;
                for (Hello h : out.hellos)
                    if (h.version == 0x0304 || h.versions.contains("TLS 1.3"))
                        tls13 = true;
                if (!tls13)
                    out.changedCipher = true;
            }
            if (type == 21 && !opaque && !out.changedCipher && len == 2) {
                out.alerts++;
                if (out.alertLabels.size() < 12)
                    out.alertLabels.add("级别 " + (b[p + 5] & 255) + " · 代码 " + (b[p + 6] & 255));
            }
            if (type == 22 && !opaque && !out.changedCipher) {
                if (hs.size() == 0)
                    hsTime = out.timeAt(p);
                if (hs.size() + len > MAX_HANDSHAKE) {
                    out.limited = true;
                    break;
                }
                hs.write(b, p + 5, len);
                byte[] h = hs.toByteArray();
                int q = 0;
                try {
                    while (q + 4 <= h.length) {
                        int n =
                            ((h[q + 1] & 255) << 16) | ((h[q + 2] & 255) << 8) | (h[q + 3] & 255);
                        if (n > MAX_HANDSHAKE - 4)
                            throw new IOException("handshake length");
                        if (q + 4 + n > h.length)
                            break;
                        int kind = h[q] & 255;
                        if (kind == 1 || kind == 2) {
                            Hello hello = hello(Arrays.copyOfRange(h, q + 4, q + 4 + n), kind == 1);
                            hello.side = side;
                            hello.time = hsTime;
                            if (out.hellos.size() < 16)
                                out.hellos.add(hello);
                            else
                                out.limited = true;
                        } else if (kind == 11 && out.certificate.isEmpty()) {
                            out.certificate = certificate(Arrays.copyOfRange(h, q + 4, q + 4 + n));
                        }
                        q += 4 + n;
                        if (q < h.length)
                            hsTime = out.timeAt(p);
                    }
                } catch (Exception e) {
                    out.malformed = true;
                    break;
                }
                hs.reset();
                hs.write(h, q, h.length - q);
            }
            p += 5 + len;
        }
        out.consumed = p;
        if (p < b.length || hs.size() > 0)
            out.partial = true;
    }
    static final class Cursor {
        final byte[] bytes;
        int p;
        Cursor(byte[] b) {
            bytes = b;
        }
        int left() {
            return bytes.length - p;
        }
        int u8() throws IOException {
            if (left() < 1)
                throw new IOException("short field");
            return bytes[p++] & 255;
        }
        int u16() throws IOException {
            return (u8() << 8) | u8();
        }
        byte[] take(int n) throws IOException {
            if (n < 0 || n > left())
                throw new IOException("short vector");
            byte[] b = Arrays.copyOfRange(bytes, p, p + n);
            p += n;
            return b;
        }
        Cursor vector(int n) throws IOException {
            return new Cursor(take(n));
        }
    }
    static final String RETRY = "cf21ad74e59a6111be1d8c021e65b891c2a211167abb8c5e079e09e2c8a8339c";
    static Hello hello(byte[] data, boolean client) throws IOException {
        Cursor c = new Cursor(data);
        Hello h = new Hello();
        h.client = client;
        h.version = c.u16();
        if (h.version < 0x0301 || h.version > 0x0303)
            throw new IOException("hello version");
        byte[] random = c.take(32);
        h.retry = !client && hex(random).equals(RETRY);
        if (client) {
            int sid = c.u8();
            if (sid > 32)
                throw new IOException("session id");
            c.take(sid);
            int n = c.u16();
            if (n < 2 || n % 2 != 0)
                throw new IOException("ciphers");
            c.take(n);
            int z = c.u8();
            if (z < 1)
                throw new IOException("compression");
            c.take(z);
        } else {
            int sid = c.u8();
            if (sid > 32)
                throw new IOException("session id");
            c.take(sid);
            h.cipher = c.u16();
            if (c.u8() != 0)
                throw new IOException("compression");
        }
        if (c.left() == 0)
            return h;
        Cursor ex = c.vector(c.u16());
        if (c.left() != 0)
            throw new IOException("hello tail");
        Set<Integer> seen = new HashSet<>();
        while (ex.left() > 0) {
            int type = ex.u16();
            Cursor e = ex.vector(ex.u16());
            if (!seen.add(type))
                throw new IOException("duplicate extension");
            if (type == 0 && client) {
                Cursor list = e.vector(e.u16());
                if (e.left() != 0)
                    throw new IOException("SNI tail");
                while (list.left() > 0) {
                    int kind = list.u8();
                    byte[] name = list.take(list.u16());
                    if (kind == 0) {
                        if (!h.sni.isEmpty() || name.length == 0 || name.length > 253)
                            throw new IOException("SNI");
                        h.sni = ascii(name);
                    }
                }
            } else if (type == 16) {
                Cursor list = e.vector(e.u16());
                if (e.left() != 0 || list.left() == 0)
                    throw new IOException("ALPN");
                List<String> names = new ArrayList<>();
                while (list.left() > 0) {
                    int n = list.u8();
                    if (n == 0)
                        throw new IOException("ALPN empty");
                    names.add(ascii(list.take(n)));
                }
                if (!client && names.size() != 1)
                    throw new IOException("server ALPN");
                h.alpn = String.join(", ", names);
            } else if (type == 43) {
                if (client) {
                    Cursor list = e.vector(e.u8());
                    if (e.left() != 0 || list.left() % 2 != 0 || list.left() < 2)
                        throw new IOException("versions");
                    List<String> vs = new ArrayList<>();
                    while (list.left() > 0) {
                        int v = list.u16();
                        if ((v & 0x0f0f) != 0x0a0a)
                            vs.add(version(v));
                    }
                    h.versions = String.join(", ", vs);
                } else {
                    h.version = e.u16();
                    if (e.left() != 0)
                        throw new IOException("version tail");
                }
            } else if (type == 0xfe0d && client)
                h.ech = true;
        }
        return h;
    }
    static String ascii(byte[] b) throws IOException {
        for (byte v : b)
            if ((v & 255) < 33 || (v & 255) > 126)
                throw new IOException("non printable metadata");
        return new String(b, StandardCharsets.US_ASCII);
    }
    static String certificate(byte[] b) {
        try {
            Cursor c = new Cursor(b);
            int all = (c.u8() << 16) | (c.u8() << 8) | c.u8();
            if (all != c.left())
                return "";
            int n = (c.u8() << 16) | (c.u8() << 8) | c.u8();
            if (n < 1 || n > 65536)
                return "";
            byte[] der = c.take(n);
            X509Certificate x =
                (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                    new ByteArrayInputStream(der));
            return "主体：" + x.getSubjectX500Principal().getName() + "\n签发者："
                + x.getIssuerX500Principal().getName() + "\n有效期：" + x.getNotBefore() + " — "
                + x.getNotAfter() + "\nSHA-256："
                + hex(MessageDigest.getInstance("SHA-256").digest(der))
                + "\n仅展示捕获证书，未验证信任链。";
        } catch (Exception e) {
            return "";
        }
    }
    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte v : b) s.append(String.format(Locale.ROOT, "%02x", v & 255));
        return s.toString();
    }
    static String version(int v) {
        return v == 0x0304 ? "TLS 1.3"
            : v == 0x0303  ? "TLS 1.2"
            : v == 0x0302  ? "TLS 1.1"
            : v == 0x0301  ? "TLS 1.0"
                           : String.format(Locale.ROOT, "0x%04X", v);
    }
    static String cipher(int c) {
        switch (c) {
            case 0x1301:
                return "TLS_AES_128_GCM_SHA256";
            case 0x1302:
                return "TLS_AES_256_GCM_SHA384";
            case 0x1303:
                return "TLS_CHACHA20_POLY1305_SHA256";
            case 0xc02f:
                return "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256";
            case 0xc030:
                return "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384";
            case 0xc02b:
                return "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256";
            case 0xc02c:
                return "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384";
            default:
                return String.format(Locale.ROOT, "0x%04X", c);
        }
    }
    static void finish(Result r) {
        List<Hello> all = new ArrayList<>();
        for (Direction d : r.directions) all.addAll(d.hellos);
        all.sort(Comparator.comparingLong(h -> h.time));
        Hello client = null, server = null;
        for (Hello h : all) {
            if (h.client) {
                if (client == null)
                    client = h;
                r.clientSide = h.side;
                r.ech |= h.ech;
                if (!h.sni.isEmpty())
                    r.sni = h.sni;
                if (!h.alpn.isEmpty())
                    r.offeredAlpn = h.alpn;
                if (!h.versions.isEmpty())
                    r.offeredVersions = h.versions;
            } else if (h.retry)
                r.retries++;
            else
                server = h;
        }
        if (server != null) {
            if (client == null)
                r.clientSide = 1 - server.side;
            else if (server.side == client.side)
                r.warnings.add("同向出现双方Hello，方向/连接边界存在歧义。");
            r.version = version(server.version);
            r.cipher = cipher(server.cipher);
            if (server.version < 0x0304 && !server.alpn.isEmpty())
                r.selectedAlpn = server.alpn;
            else if (server.version == 0x0304)
                r.selectedAlpn = "TLS 1.3 中此项已加密";
            if (client != null && server.time >= client.time)
                r.helloMicros = server.time - client.time;
            String cert = r.directions[server.side].certificate;
            if (server.version == 0x0304)
                r.certificate = "TLS 1.3 的证书消息已加密，当前无法读取。";
            else if (!cert.isEmpty())
                r.certificate = cert;
        }
        r.status = r.clientSide >= 0
            ? "已观察到有效Hello；仅分析可见握手，不代表完整握手成功或正文已解密。"
            : r.directions[0].framed || r.directions[1].framed
            ? "记录头符合TLS形态，但没有完整Hello，暂不能确认版本与域名。"
            : "未在连续前缀中确认TLS；可能为其他协议，也可能从连接中途开始或缺失握手。";
        if (r.limited)
            r.warnings.add("达到扫描/内存上限，结果只覆盖有限窗口。");
        if (r.skipped > 0)
            r.warnings.add("跳过 " + r.skipped + " 条无法安全读取/解析的候选记录。");
        if (r.truncated > 0)
            r.warnings.add("候选记录有 " + r.truncated + " 条截断，无法保证完整。");
        for (int i = 0; i < 2; i++) {
            Direction d = r.directions[i];
            String prefix = "方向" + (i + 1) + "：";
            if (!d.syn)
                r.warnings.add(prefix + "未观察到SYN，可能从中途开始。");
            if (d.gap)
                r.warnings.add(prefix + "存在TCP字节缺口，只解析缺口前的连续部分。");
            if (d.conflict)
                r.warnings.add(prefix + "重叠字节冲突，已停止该方向解析。");
            if (d.limited)
                r.warnings.add(prefix + "达到重组/握手长度上限。");
            if (d.partial)
                r.warnings.add(prefix + "末尾记录或握手不完整。");
            if (d.malformed)
                r.warnings.add(prefix + "边界/结构不匹配，未尝试盲目跳过字节寻找TLS头。");
        }
        if (r.ech)
            r.warnings.add("ClientHello携带ECH扩展；可见SNI可能是外层名称，不能当作真实目标域名。");
    }
}
