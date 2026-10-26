package io.github.netcap.app;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** 主机离线真实文件检查；不把它当作手机 Root、窗口或无线切换验证。 */
public final class WorkbenchChecks {
    static int checks;
    static File root;
    static void require(boolean ok, String msg) throws Exception {
        checks++;
        if (!ok)
            throw new Exception(msg);
    }
    static File dir(String name) throws Exception {
        File d = new File(root, name);
        if (!d.mkdirs())
            throw new Exception("mkdir " + d);
        return d;
    }
    static byte[] udp(byte[] data, boolean v6) {
        int h = v6 ? 40 : 20;
        byte[] p = new byte[h + 8 + data.length];
        p[0] = (byte) (v6 ? 0x60 : 0x45);
        if (v6) {
            p[4] = (byte) ((8 + data.length) >> 8);
            p[5] = (byte) (8 + data.length);
            p[6] = 17;
            p[23] = 1;
            p[39] = 2;
        } else {
            p[2] = (byte) (p.length >> 8);
            p[3] = (byte) p.length;
            p[9] = 17;
            p[12] = 10;
            p[15] = 1;
            p[16] = 10;
            p[19] = 2;
        }
        p[h] = 0x30;
        p[h + 1] = 0x39;
        p[h + 2] = (byte) 0xd4;
        p[h + 3] = 0x31;
        p[h + 4] = (byte) ((8 + data.length) >> 8);
        p[h + 5] = (byte) (8 + data.length);
        System.arraycopy(data, 0, p, h + 8, data.length);
        return p;
    }
    static byte[] tcp(long seq, int flags, byte[] data) {
        byte[] p = new byte[40 + data.length];
        p[0] = 0x45;
        p[2] = (byte) (p.length >> 8);
        p[3] = (byte) p.length;
        p[9] = 6;
        p[12] = 10;
        p[15] = 1;
        p[16] = 10;
        p[19] = 2;
        p[20] = 0x30;
        p[21] = 0x39;
        p[22] = 0x01;
        p[23] = (byte) 0xbb;
        for (int i = 0; i < 4; i++) p[24 + i] = (byte) (seq >> (24 - 8 * i));
        p[32] = 0x50;
        p[33] = (byte) flags;
        System.arraycopy(data, 0, p, 40, data.length);
        return p;
    }
    static byte[] kcp(long seq) {
        ByteBuffer b = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(1234)
            .put((byte) 81)
            .put((byte) 0)
            .putShort((short) 128)
            .putInt(100)
            .putInt((int) seq)
            .putInt(0)
            .putInt(4)
            .putInt(11);
        return udp(b.array(), false);
    }
    static byte[] fragment(int offset, boolean more, int size) {
        byte[] p = new byte[20 + size];
        p[0] = 0x45;
        p[2] = (byte) (p.length >> 8);
        p[3] = (byte) p.length;
        p[5] = 1;
        p[9] = 17;
        p[12] = 10;
        p[15] = 1;
        p[16] = 10;
        p[19] = 2;
        int f = offset / 8 | (more ? 8192 : 0);
        p[6] = (byte) (f >> 8);
        p[7] = (byte) f;
        return p;
    }
    static byte[] pcap(List<byte[]> packets) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0xa1b2c3d4)
            .putShort((short) 2)
            .putShort((short) 4)
            .putInt(0)
            .putInt(0)
            .putInt(262144)
            .putInt(101);
        out.write(h.array());
        int i = 0;
        for (byte[] p : packets) {
            ByteBuffer rh = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            rh.putInt(1700000000 + i++).putInt(100).putInt(p.length).putInt(p.length);
            out.write(rh.array());
            out.write(p);
        }
        return out.toByteArray();
    }
    static File session(String name, List<byte[]> packets) throws Exception {
        File d = dir(name);
        PacketStore.importPcap(
            new ByteArrayInputStream(pcap(packets)), d, new JSONObject().put("state", "imported"));
        return d;
    }
    static String digest(File f) throws Exception {
        return Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(
                Files.readAllBytes(f.toPath())));
    }
    public static void main(String[] args) throws Exception {
        root = new File(args[0]);
        root.mkdirs();
        List<byte[]> fixtures = new ArrayList<>();
        for (int i = 0; i < 5000; i++)
            fixtures.add(
                udp(("{\"event\":\"fixture\",\"seq\":" + i + "}").getBytes(StandardCharsets.UTF_8),
                    i % 2 == 1));
        File a = session("mixed", fixtures);
        String idxBefore = digest(new File(a, "packets.idx")),
               pcapBefore = digest(PacketStore.segment(a, 1));
        PacketStore.Query legacy = PacketStore.query(a, -1, true, "", 0, () -> false);
        require(legacy.records.size() == 80, "legacy page80");
        PacketFilter filter = new PacketFilter();
        Set<Long> ids = new HashSet<>();
        long cursor = -1;
        int pages = 0;
        do {
            PacketStore.Query q = PacketStore.query(a, cursor, filter, () -> false);
            for (PacketStore.Record r : q.records) require(ids.add(r.id), "duplicate page " + r.id);
            cursor = q.next;
            pages++;
        } while (cursor >= 0);
        require(ids.size() == 5000 && pages == 40, "auto paging completeness");
        PacketStore.Query recent = PacketStore.newer(a, 4900, filter, () -> false);
        require(recent.records.size() == 100 && recent.records.get(0).id == 4901, "reverse page");
        filter.protocol = 6;
        PacketStore.Query none = PacketStore.query(a, -1, filter, () -> false);
        require(none.records.isEmpty() && none.scanned == 4096 && none.next == 903,
            "bounded empty scan");
        filter.protocol = 0;
        filter.magic = "fixture".getBytes(StandardCharsets.UTF_8);
        require(
            PacketStore.query(a, -1, filter, () -> false).records.size() == 128, "payload magic");
        filter.prefix = true;
        require(PacketStore.query(a, -1, filter, () -> false).records.isEmpty(),
            "magic prefix rejects middle");
        filter.magic = "{\"event\"".getBytes(StandardCharsets.UTF_8);
        require(
            PacketStore.query(a, -1, filter, () -> false).records.size() == 128, "magic prefix");
        filter = new PacketFilter();
        filter.readable = 1;
        require(
            PacketStore.query(a, -1, filter, () -> false).records.size() == 128, "readable UTF8");
        require(
            !PacketFilter.isReadable(new byte[] {0, 1, 2}) && !PacketFilter.isReadable(new byte[0]),
            "nontext");
        filter.readable = 2;
        require(PacketStore.query(a, -1, filter, () -> false).records.isEmpty(), "binary filter");
        filter = new PacketFilter();
        filter.ids = new HashSet<>(Arrays.asList(1L, 100L, 5000L));
        PacketStore.Query saved = PacketStore.query(a, -1, filter, () -> false);
        require(saved.records.size() == 3 && saved.scanned == 3 && saved.records.get(0).id == 5000,
            "sparse saved no full scan");
        Map<Long, String> notes = PacketNotes.edit(a, Arrays.asList(1L, 5000L), false, null);
        require(notes.size() == 2, "save");
        PacketNotes.edit(a, Collections.singleton(1L), false, "命名\"特殊字符");
        require(PacketNotes.read(a).get(1L).equals("命名\"特殊字符"), "rename");
        PacketNotes.edit(a, Collections.singleton(5000L), true, null);
        require(PacketNotes.read(a).size() == 1, "remove saved");
        require(idxBefore.equals(digest(new File(a, "packets.idx")))
                && pcapBefore.equals(digest(PacketStore.segment(a, 1))),
            "notes cannot mutate original");
        require(PacketStore.groups(a, 0, () -> false).size() == 2, "IPv4 IPv6 distinct flows");
        filter = new PacketFilter();
        filter.groupKey = PacketFilter.group(legacy.records.get(0), 0);
        PacketStore.Query related = PacketStore.query(a, -1, filter, () -> false);
        for (PacketStore.Record r : related.records) require(r.version == 6, "related isolated");
        require(PacketStore.groups(a, 1, () -> false).size() == 1, "protocol grouping");
        try (RandomAccessFile idx = new RandomAccessFile(new File(a, "packets.idx"), "rw")) {
            for (int i = 0; i < 5000; i++) {
                idx.seek(i * 64L + 61);
                idx.write(i % 2);
            }
        }
        filter = new PacketFilter();
        filter.direction = 1;
        PacketStore.Query up = PacketStore.query(a, -1, filter, () -> false);
        for (PacketStore.Record r : up.records) require(r.dir == 1, "direction");
        JSONObject report = IntegrityReport.generate(a, PacketStore.manifest(a), () -> false);
        require(
            report.getJSONArray("errors").length() == 0 && report.getLong("recordsChecked") == 5000,
            "full file report");
        File gap = session("tcp-gap",
            Arrays.asList(
                tcp(100, 2, new byte[0]), tcp(101, 16, new byte[10]), tcp(121, 17, new byte[5])));
        report = IntegrityReport.generate(gap, PacketStore.manifest(gap), () -> false);
        require(report.getJSONArray("tcp").getJSONObject(0).getLong("gapUnits") == 10, "TCP gap");
        File repaired = session("tcp-reorder",
            Arrays.asList(tcp(100, 2, new byte[0]), tcp(121, 17, new byte[5]),
                tcp(101, 16, new byte[10]), tcp(111, 16, new byte[10]),
                tcp(111, 16, new byte[10])));
        report = IntegrityReport.generate(repaired, PacketStore.manifest(repaired), () -> false);
        require(report.getJSONArray("tcp").getJSONObject(0).getLong("gapUnits") == 0,
            "late repair and retransmission");
        File wrap = session("tcp-wrap",
            Arrays.asList(tcp(0xfffffff0L, 16, new byte[16]), tcp(0, 16, new byte[10])));
        report = IntegrityReport.generate(wrap, PacketStore.manifest(wrap), () -> false);
        require(report.getJSONArray("tcp").getJSONObject(0).getLong("gapUnits") == 0, "TCP wrap32");
        File kc = session("kcp-gap", Arrays.asList(kcp(3), kcp(5)));
        report = IntegrityReport.generate(kc, PacketStore.manifest(kc), () -> false);
        require(
            report.getJSONArray("standardKcpCandidates").getJSONObject(0).getLong("gapUnits") == 1,
            "KCP candidate SN gap");
        File frag = session("fragment-incomplete", Arrays.asList(fragment(0, true, 8)));
        report = IntegrityReport.generate(frag, PacketStore.manifest(frag), () -> false);
        require(report.getLong("incompleteFragmentGroups") == 1, "incomplete fragment");
        File fg = session(
            "fragment-complete", Arrays.asList(fragment(0, true, 8), fragment(8, false, 8)));
        report = IntegrityReport.generate(fg, PacketStore.manifest(fg), () -> false);
        require(report.getLong("incompleteFragmentGroups") == 0, "complete fragment observation");
        File malformed =
            session("malformed-ip", Collections.singletonList(udp(new byte[] {1}, false)));
        try (
            RandomAccessFile file = new RandomAccessFile(PacketStore.segment(malformed, 1), "rw")) {
            file.setLength(file.length() - 1);
        }
        report = IntegrityReport.generate(malformed, PacketStore.manifest(malformed), () -> false);
        require(report.getJSONArray("errors").length() > 0, "truncated file report");
        File unknown = new File(a, "unattributed");
        unknown.mkdir();
        Files.write(new File(unknown, "evidence.txt").toPath(), new byte[] {1, 2});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PacketStore.export(a, out, "zip");
        boolean included = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null)
                if (e.getName().equals("unattributed/evidence.txt"))
                    included = true;
        }
        require(included, "recursive ZIP unknown evidence");
        out = new ByteArrayOutputStream();
        PacketStore.export(a, out, "pcap");
        require(Arrays.equals(out.toByteArray(), pcap(fixtures)), "PCAP export unchanged");
        File prefix = session("literal-hex-text",
            Collections.singletonList(udp("hex: ABC".getBytes(StandardCharsets.UTF_8), false)));
        filter = new PacketFilter();
        filter.text = "hex:";
        require(PacketStore.query(prefix, -1, filter, () -> false).records.size() == 1,
            "literal hex text mode");
        File invalid =
            session("invalid-udp", Collections.singletonList(udp(new byte[] {1}, false)));
        try (RandomAccessFile raw = new RandomAccessFile(PacketStore.segment(invalid, 1), "rw")) {
            raw.seek(24 + 16 + 24);
            raw.write(0xff);
            raw.write(0xff);
        }
        report = IntegrityReport.generate(invalid, PacketStore.manifest(invalid), () -> false);
        require(report.getLong("parseFailedSaved") == 1,
            "saved invalid UDP separate from unknown business protocol");
        File reused = session("fragment-reused",
            Arrays.asList(fragment(0, true, 8), fragment(0, true, 8), fragment(8, false, 8)));
        report = IntegrityReport.generate(reused, PacketStore.manifest(reused), () -> false);
        require(report.getLong("incompleteFragmentGroups") == 1,
            "fragment overlap cannot claim complete");
        byte[] probePayload = ByteBuffer.allocate(29)
                                  .put("NCAPTEST".getBytes(StandardCharsets.US_ASCII))
                                  .put(new byte[16])
                                  .put((byte) 1)
                                  .putInt(0)
                                  .array();
        File probe = session(
            "probe-sequences", Arrays.asList(udp(probePayload, false), udp(probePayload, false)));
        report = IntegrityReport.generate(probe, PacketStore.manifest(probe), () -> false);
        require(report.getJSONArray("testStreams").getJSONObject(0).getLong("uniqueSequences") == 1,
            "loopback sequence dedup");
        PacketFilter lengthFilter = new PacketFilter();
        lengthFilter.minBytes = legacy.records.get(0).caplen;
        lengthFilter.maxBytes = lengthFilter.minBytes;
        PacketStore.Query sameLength = PacketStore.query(a, -1, lengthFilter, () -> false);
        require(!sameLength.records.isEmpty(), "length filter has matches");
        require(sameLength.records.stream().allMatch(x -> x.caplen == lengthFilter.minBytes),
            "exact byte length filter");
        System.out.println("PASS " + checks
            + (" assertions; pagination, filters, notes, grouping, TCP/KCP/fragment reports and "
                + "exports"));
    }
}
