package io.github.netcap.app;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

/** Host tests: genuine TLS-library traces plus adversarial framing/reassembly and palette roles. */
public final class TlsThemeChecks {
    static int checks;
    static void need(boolean value, String message) throws Exception {
        checks++;
        if (!value)
            throw new Exception(message);
    }
    static byte[] join(byte[]... parts) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p);
        return b.toByteArray();
    }
    static byte[] b(int... ints) {
        byte[] b = new byte[ints.length];
        for (int i = 0; i < ints.length; i++) b[i] = (byte) ints[i];
        return b;
    }
    static byte[] v16(byte[] data) throws Exception {
        return join(b(data.length >> 8, data.length), data);
    }
    static byte[] ext(int type, byte[] data) throws Exception {
        return join(b(type >> 8, type), v16(data));
    }
    static byte[] handshake(int kind, byte[] data) throws Exception {
        return join(b(kind, data.length >> 16, data.length >> 8, data.length), data);
    }
    static byte[] record(int type, byte[] data) throws Exception {
        return join(b(type, 3, 3, data.length >> 8, data.length), data);
    }
    static byte[] extensions(boolean client) throws Exception {
        byte[] alpn = ext(16, v16(join(b(2), "h2".getBytes(StandardCharsets.US_ASCII))));
        return client
            ? join(ext(0,
                       v16(join(
                           b(0), v16("test.netcap.invalid".getBytes(StandardCharsets.US_ASCII))))),
                  alpn, ext(43, b(4, 3, 4, 3, 3)))
            : ext(43, b(3, 4));
    }
    static byte[] hello(boolean client, byte[] extensions, byte[] random) throws Exception {
        byte[] prefix = join(b(3, 3), random, b(0));
        return handshake(client ? 1 : 2,
            join(prefix, client ? b(0, 2, 0x13, 1, 1, 0) : b(0x13, 1, 0), v16(extensions)));
    }
    static TlsInspector.Direction parse(byte[] data) {
        TlsInspector.Direction d = new TlsInspector.Direction();
        d.bytes = data;
        d.times.put(0, 1234L);
        TlsInspector.parse(d, 0);
        return d;
    }
    static TlsInspector.Segment seg(long seq, byte[] data, int flags) {
        return new TlsInspector.Segment(0, seq, data, 1234, flags, 1);
    }
    static TlsInspector.Result from(List<TlsInspector.Segment> segments) {
        TlsInspector.Result r = new TlsInspector.Result();
        for (int side = 0; side < 2; side++) {
            r.directions[side] = TlsInspector.reassemble(segments, side);
            TlsInspector.parse(r.directions[side], side);
        }
        TlsInspector.finish(r);
        return r;
    }
    static double lum(int color) {
        double y = 0;
        double[] weights = {.0722, .7152, .2126};
        for (int i = 0; i < 3; i++) {
            double c = ((color >>> (i * 8)) & 255) / 255.0;
            c = c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
            y += c * weights[i];
        }
        return y;
    }
    static double contrast(int a, int b) {
        double x = lum(a), y = lum(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }
    public static void main(String[] args) throws Exception {
        WorkbenchChecks.root = new File(args[0]);
        WorkbenchChecks.root.mkdirs();
        byte[] ch = hello(true, extensions(true), new byte[32]);
        byte[] wire = record(22, ch);
        TlsInspector.Direction d = parse(wire);
        need(d.hellos.size() == 1, "valid ClientHello");
        need(d.hellos.get(0).sni.equals("test.netcap.invalid"), "SNI parsed");
        need(d.hellos.get(0).alpn.equals("h2"), "offered ALPN");
        need(d.hellos.get(0).versions.equals("TLS 1.3, TLS 1.2"),
            "supported versions not legacy 0303");
        for (int cut = 1; cut < wire.length; cut++) {
            List<TlsInspector.Segment> pieces = Arrays.asList(seg(100, new byte[0], 2),
                seg(101 + cut, Arrays.copyOfRange(wire, cut, wire.length), 24),
                seg(101, Arrays.copyOfRange(wire, 0, cut), 24));
            TlsInspector.Result r = from(pieces);
            need(r.clientSide == 0 && !r.directions[0].gap, "reorder/split boundary " + cut);
            need(Arrays.equals(r.directions[0].bytes, wire), "reassembled exact " + cut);
        }
        byte[] splitRecords = join(record(22, Arrays.copyOfRange(ch, 0, 11)),
            record(22, Arrays.copyOfRange(ch, 11, ch.length)));
        need(parse(splitRecords).hellos.size() == 1, "handshake across TLS records");
        need(parse(join(wire, wire)).hellos.size() == 2, "multiple records");
        List<TlsInspector.Segment> dup = Arrays.asList(seg(101, wire, 24), seg(101, wire, 24));
        need(from(dup).directions[0].duplicate == 1, "duplicate counted");
        byte[] changed = wire.clone();
        changed[7] ^= 1;
        TlsInspector.Result conflict =
            from(Arrays.asList(seg(101, wire, 24), seg(101, changed, 24)));
        need(conflict.directions[0].conflict && conflict.clientSide < 0,
            "conflicting overlap no false hello");
        TlsInspector.Result gap = from(Arrays.asList(seg(100, new byte[0], 2), seg(102, wire, 24)));
        need(gap.directions[0].gap && gap.clientSide < 0, "gap before hello not skipped");
        TlsInspector.Result wrap =
            from(Arrays.asList(seg(0xfffffffeL, Arrays.copyOfRange(wire, 0, 2), 24),
                seg(0, Arrays.copyOfRange(wire, 2, wire.length), 24)));
        need(wrap.clientSide == 0, "TCP 32bit wrap");
        for (int cut = 0; cut < wire.length; cut++)
            need(parse(Arrays.copyOf(wire, cut)).hellos.isEmpty(), "truncated hello " + cut);
        need(parse(
                 record(22, hello(true, join(extensions(true), ext(43, b(2, 3, 4))), new byte[32])))
                 .malformed,
            "duplicate ext");
        need(parse(record(22, hello(true, ext(16, b(0, 1, 0)), new byte[32]))).malformed,
            "zero ALPN");
        need(parse(record(22, hello(true, ext(0, b(0, 20, 0, 0, 19, 65)), new byte[32]))).malformed,
            "short SNI");
        need(parse(
                 record(22, hello(true, ext(0, v16(join(b(0), v16(b(65, 10, 66))))), new byte[32])))
                 .malformed,
            "SNI control rejected");
        TlsInspector.Result ech = from(Arrays.asList(seg(1,
            record(22, hello(true, join(extensions(true), ext(0xfe0d, b(0, 1))), new byte[32])),
            24)));
        need(ech.ech && ech.warnings.stream().anyMatch(s -> s.contains("ECH")), "ECH caveat");
        byte[] retry = new byte[32];
        for (int i = 0; i < 32; i++)
            retry[i] = (byte) Integer.parseInt(TlsInspector.RETRY.substring(i * 2, i * 2 + 2), 16);
        TlsInspector.Result hrr =
            from(Arrays.asList(new TlsInspector.Segment(0, 1, wire, 100, 24, 1),
                new TlsInspector.Segment(
                    1, 1, record(22, hello(false, extensions(false), retry)), 200, 24, 2)));
        need(hrr.retries == 1 && hrr.version.equals("未确认") && hrr.helloMicros < 0,
            "HRR not final ServerHello");
        need(parse(record(21, b(2, 40))).alerts == 1, "visible alert");
        need(parse(join(record(20, b(1)), record(21, b(2, 40)))).alerts == 0,
            "encrypted alert not decoded");
        need(parse(join(record(23, new byte[4]), record(22, ch))).hellos.isEmpty(),
            "encrypted flight not decoded");
        need(parse(b(22, 3, 3, 255, 255)).malformed, "record cap");
        need(parse(record(20, b(2))).malformed, "bad CCS rejected");
        need(parse(join(wire, record(20, b(1)), wire)).hellos.size() == 2,
            "TLS13 compatibility CCS ignored");
        byte[] tooLong = record(22, b(1, 4, 0, 0));
        need(parse(tooLong).malformed, "handshake size limit");
        byte[] noSni = record(22, hello(true, ext(43, b(2, 3, 4)), new byte[32]));
        need(parse(noSni).hellos.get(0).sni.isEmpty(), "no SNI not invented");
        List<byte[]> many = new ArrayList<>();
        many.add(WorkbenchChecks.tcp(100, 2, new byte[0]));
        many.add(WorkbenchChecks.tcp(101, 24, wire));
        for (int i = 0; i < TlsInspector.MAX_SEGMENTS; i++)
            many.add(WorkbenchChecks.tcp(101 + wire.length, 16, new byte[0]));
        File longFlow = WorkbenchChecks.session("bounded-late-anchor", many);
        PacketStore.Record late =
            PacketStore.query(longFlow, -1, new PacketFilter(), () -> false).records.get(0);
        TlsInspector.Result bounded = TlsInspector.inspect(late, () -> false);
        need(bounded.foundAnchor && bounded.clientSide == 0 && bounded.limited,
            "late selected record retains known bounded epoch");
        need(bounded.packets <= TlsInspector.MAX_SEGMENTS, "segment budget");
        List<byte[]> wrongInterface = Arrays.asList(WorkbenchChecks.tcp(100, 2, new byte[0]),
            WorkbenchChecks.tcp(101, 24, wire),
            WorkbenchChecks.tcp(101 + wire.length, 24, b(1, 2, 3, 4, 5)));
        File iface = WorkbenchChecks.session("interface-isolation", wrongInterface);
        try (RandomAccessFile idx = new RandomAccessFile(new File(iface, "packets.idx"), "rw")) {
            // First two packets are interface 0; selected last packet is a different interface.
            idx.seek(2 * PacketStore.ROW + 40);
            idx.write(new byte[] {9, 0, 0, 0});
        }
        PacketStore.Record isolated =
            PacketStore.query(iface, -1, new PacketFilter(), () -> false).records.get(0);
        need(TlsInspector.inspect(isolated, () -> false).clientSide < 0, "interfaces not merged");
        Random random = new Random(20261028);
        for (int i = 0; i < 1000; i++) {
            byte[] fuzz = new byte[random.nextInt(400)];
            random.nextBytes(fuzz);
            need(parse(fuzz).hellos.isEmpty(), "random no false hello " + i);
        }
        List<TlsInspector.Segment> huge =
            Arrays.asList(seg(1, new byte[TlsInspector.MAX_DIRECTION + 1], 24));
        need(from(huge).directions[0].limited, "direction cap");
        byte[] p = WorkbenchChecks.tcp(101, 24, wire);
        need(TlsInspector.transport(p) == 20, "IPv4 transport");
        p[6] = 0x20;
        need(TlsInspector.transport(p) < 0, "IPv4 fragments excluded");
        p[6] = 0;
        p[32] = 0x40;
        need(TlsInspector.transport(p) < 0, "bad TCP offset");
        File reused = WorkbenchChecks.session("reuse",
            Arrays.asList(WorkbenchChecks.tcp(100, 2, new byte[0]),
                WorkbenchChecks.tcp(101, 24, wire), WorkbenchChecks.tcp(900, 2, new byte[0]),
                WorkbenchChecks.tcp(
                    901, 24, "HTTP/1.1 200 OK".getBytes(StandardCharsets.US_ASCII))));
        PacketStore.Record anchor =
            PacketStore.query(reused, -1, new PacketFilter(), () -> false).records.get(0);
        TlsInspector.Result r = TlsInspector.inspect(anchor, () -> false);
        need(r.clientSide < 0 && r.packets == 2, "reused tuple not old TLS");
        need(TlsInspector.inspect(anchor, () -> true).cancelled, "scan cancellation");
        byte[] random443 = WorkbenchChecks.tcp(1, 24, b(1, 2, 3, 4, 5, 6));
        File notTls = WorkbenchChecks.session("port-only", Arrays.asList(random443));
        anchor = PacketStore.query(notTls, -1, new PacketFilter(), () -> false).records.get(0);
        need(TlsInspector.inspect(anchor, () -> false).clientSide < 0, "443 not sufficient");
        for (ThemePalette t : ThemePalette.ALL) {
            need(ThemePalette.find(t.id) == t, "theme persisted key");
            for (int surface :
                new int[] {t.bg, t.workspace, t.modal, t.surface, t.active, t.badge}) {
                need(contrast(t.text, surface) >= 4.5, "text contrast " + t.id);
                need(contrast(t.muted, surface) >= 4.5, "secondary contrast " + t.id);
                need(contrast(t.accent, surface) >= 4.5, "accent contrast " + t.id);
            }
            need(contrast(t.onAccent, t.accent) >= 4.5, "action contrast " + t.id);
            need(lum(t.surface) < lum(t.workspace), "cards darker than workspace " + t.id);
        }
        need(
            ThemePalette.find("old-unknown-id") == ThemePalette.ALL[0], "theme migration fallback");
        if (args.length > 1) {
            for (String label : new String[] {"tls12", "tls13"}) {
                File f = new File(WorkbenchChecks.root, label);
                f.mkdirs();
                try (InputStream in = Files.newInputStream(Paths.get(args[1], label + ".pcap"))) {
                    PacketStore.importPcap(in, f, new JSONObject());
                }
                String before = WorkbenchChecks.digest(PacketStore.segment(f, 1));
                anchor = PacketStore.query(f, -1, new PacketFilter(), () -> false).records.get(0);
                r = TlsInspector.inspect(anchor, () -> false);
                need(r.sni.equals("test.netcap.invalid"), label + " real SNI");
                need(r.version.equals(label.equals("tls12") ? "TLS 1.2" : "TLS 1.3"),
                    label + " real selected version");
                need(r.offeredAlpn.contains("h2"), label + " real ALPN");
                need(!r.directions[0].gap && !r.directions[1].gap,
                    label + " reordered stream complete");
                need(r.helloMicros >= 0, label + " observed flight timing");
                if (label.equals("tls12")) {
                    need(r.selectedAlpn.equals("h2"), "real TLS12 selected ALPN");
                    need(r.certificate.contains("test.netcap.invalid"), "real TLS12 cert");
                } else {
                    need(r.selectedAlpn.contains("已加密"), "TLS13 ALPN not invented");
                    need(r.certificate.contains("已加密"), "TLS13 cert not invented");
                }
                need(before.equals(WorkbenchChecks.digest(PacketStore.segment(f, 1))),
                    label + " raw capture unchanged");
                Files.write(new File(WorkbenchChecks.root, label + "-analysis.txt").toPath(),
                    (r.status + "\n" + r.version + "\n" + r.sni + "\n" + r.certificate + "\n"
                        + r.warnings)
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
        System.out.println(
            "PASS " + checks + " TLS/theme assertions; genuine fixtures=" + (args.length > 1));
    }
}
