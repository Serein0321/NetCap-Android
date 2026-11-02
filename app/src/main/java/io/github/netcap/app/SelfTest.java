package io.github.netcap.app;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** 仅通过 adb instrumentation 显式运行；验证真实 Android 文件解析与导出，而非界面占位数据。 */
public final class SelfTest extends Instrumentation {
    private Bundle arguments;
    @Override
    public void onCreate(Bundle args) {
        arguments = args;
        start();
    }
    private void require(boolean ok, String message) throws Exception {
        if (!ok)
            throw new Exception(message);
    }
    private File dir(Context c, String name) throws Exception {
        File f = new File(c.getCacheDir(), "checks/" + name + "_" + UUID.randomUUID());
        require(f.mkdirs(), "无法创建测试目录");
        return f;
    }
    private byte[] fixture(int count) throws Exception {
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
        for (int i = 0; i < count; i++) {
            byte[] payload = ("{\"message\":\"fixture\",\"sequence\":" + i + "}")
                                 .getBytes(StandardCharsets.UTF_8);
            byte[] p = new byte[28 + payload.length];
            p[0] = 0x45;
            p[3] = (byte) p.length;
            p[9] = 17;
            p[12] = 10;
            p[15] = 1;
            p[16] = 10;
            p[19] = 2;
            p[20] = 1;
            p[22] = 2;
            p[25] = (byte) (8 + payload.length);
            System.arraycopy(payload, 0, p, 28, payload.length);
            ByteBuffer r = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            r.putInt(1700000000 + i).putInt(100).putInt(p.length).putInt(p.length);
            out.write(r.array());
            out.write(p);
        }
        return out.toByteArray();
    }
    @Override
    public void onStart() {
        Bundle result = new Bundle();
        StringBuilder log = new StringBuilder();
        try {
            Context c = getTargetContext();
            if (arguments != null && arguments.containsKey("storage")) {
                storageCheck(c, "granted".equals(arguments.getString("storage")), log);
                result.putString("stream", log.toString());
                finish(-1, result);
                return;
            }
            File a = dir(c, "source");
            byte[] pcap = fixture(175);
            JSONObject m = new JSONObject().put("label", "自动检查");
            PacketStore.importPcap(new ByteArrayInputStream(pcap), a, m);
            require(PacketStore.count(a) == 175, "索引包数");
            PacketStore.Query first = PacketStore.query(a, -1, true, "", 0, () -> false);
            require(first.records.size() == 80 && first.records.get(0).id == 175, "首分页及顺序");
            PacketStore.Query next = PacketStore.query(a, first.next, true, "", 0, () -> false);
            require(
                next.records.size() == 80 && next.records.get(0).id == 95, "第二页无重复或遗漏");
            require(PacketStore.query(a, -1, true, "", 0, () -> false, 174).records.size() == 1,
                "清空视图的序号界限");
            require(PacketStore.count(a) == 175, "清空视图不得删除磁盘记录");
            PacketStore.Query text =
                PacketStore.query(a, -1, true, "sequence\":17}", 0, () -> false);
            require(text.records.size() == 1 && text.records.get(0).id == 18, "全文搜索");
            PacketStore.Query hex =
                PacketStore.query(a, -1, true, "hex: 66697874757265", 17, () -> false);
            require(hex.records.size() == 80, "完整字节搜索");
            require(
                PacketStore.query(a, -1, true, "", 6, () -> false).records.isEmpty(), "协议筛选");
            log.append("分页、顺序、全文/字节搜索、协议筛选：通过\n");
            PacketStore.Record r = first.records.get(0);
            byte[] payload = PacketStore.payload(r, PacketStore.bytes(r));
            require(PacketStore.readable(payload).contains("174"), "JSON 可读视图");
            require(
                PacketStore.diff(new byte[] {1, 2}, new byte[] {1, 3, 4}).contains("不同位置：2"),
                "差异计算");
            require(PacketStore.flows(a, () -> false).size() == 1, "连接归类");
            log.append("可读内容、连接汇总、载荷差异：通过\n");
            ByteArrayOutputStream exported = new ByteArrayOutputStream();
            PacketStore.export(a, exported, "pcap");
            File b = dir(c, "roundtrip");
            PacketStore.importPcap(
                new ByteArrayInputStream(exported.toByteArray()), b, new JSONObject());
            require(PacketStore.count(b) == 175, "PCAP 导出回读");
            ByteArrayOutputStream json = new ByteArrayOutputStream();
            PacketStore.export(a, json, "jsonl");
            require(json.toString("UTF-8").split("\n").length == 175, "JSONL 导出数量");
            ByteArrayOutputStream zip = new ByteArrayOutputStream();
            PacketStore.export(a, zip, "zip");
            require(zip.size() > 0, "ZIP 导出");
            log.append("PCAP 导出回读、JSONL 数量、ZIP：通过\n");
            byte[] truncated = Arrays.copyOf(pcap, pcap.length - 1);
            boolean rejected = false;
            try {
                PacketStore.importPcap(
                    new ByteArrayInputStream(truncated), dir(c, "broken"), new JSONObject());
            } catch (IOException e) {
                rejected = true;
            }
            require(rejected, "截断文件必须报告错误");
            byte[] shortTcp = new byte[20];
            shortTcp[0] = 0x45;
            shortTcp[3] = 20;
            shortTcp[9] = 6;
            require(PacketStore.decode(shortTcp).payloadLen == 0, "短 TCP 头边界");
            log.append("截断记录拒绝、短传输头：通过\n");
            String fixture = arguments == null ? null : arguments.getString("fixture");
            if (fixture != null) {
                File actual = dir(c, "actual");
                try (InputStream in = new FileInputStream(fixture)) {
                    PacketStore.importPcap(in, actual, new JSONObject());
                }
                require(PacketStore.count(actual) == 15006, "工作区样本应有 15006 包");
                log.append("工作区 15006 包样本完整导入：通过\n");
            }
            File core = new File(c.getApplicationInfo().nativeLibraryDir, "libnetcap_core.so");
            java.lang.Process process = new ProcessBuilder(core.getAbsolutePath(), "selftest")
                                            .redirectErrorStream(true)
                                            .start();
            StringBuilder nativeLog = new StringBuilder();
            try (BufferedReader in = new BufferedReader(
                     new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) nativeLog.append(line);
            }
            require(process.waitFor() == 0, "原生解析检查：" + nativeLog);
            log.append("原生 ELF 可执行与 65536 组解析边界：通过\n");
            File nativeDir = dir(c, "native_writer");
            java.lang.Process disk = new ProcessBuilder(
                core.getAbsolutePath(), "writer-test", nativeDir.getAbsolutePath())
                                         .redirectErrorStream(true)
                                         .start();
            StringBuilder diskLog = new StringBuilder();
            int concurrentReads = 0;
            long deadline = android.os.SystemClock.uptimeMillis() + 20000;
            while (disk.isAlive()) {
                if (android.os.SystemClock.uptimeMillis() > deadline) {
                    disk.destroy();
                    throw new Exception("原生写盘测试超时");
                }
                long available = PacketStore.count(nativeDir);
                if (available > 0) {
                    try (RandomAccessFile index =
                             new RandomAccessFile(new File(nativeDir, "packets.idx"), "r")) {
                        PacketStore.Record last = PacketStore.row(index, nativeDir, available - 1);
                        require(PacketStore.bytes(last).length == last.caplen,
                            "已公布索引必须可读取完整原包");
                        concurrentReads++;
                    }
                }
                Thread.sleep(5);
            }
            try (BufferedReader in = new BufferedReader(
                     new InputStreamReader(disk.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) diskLog.append(line);
            }
            require(disk.waitFor() == 0, "原生写盘检查：" + diskLog);
            require(concurrentReads > 0, "未观察到写盘期间的索引读取");
            log.append("边写边读索引与原包：").append(concurrentReads).append(" 次通过\n");
            require(PacketStore.count(nativeDir) == 1200, "原生索引包数");
            require(PacketStore.segment(nativeDir, 2).isFile(), "64 MiB 自动分段");
            PacketStore.Query nativeRecords =
                PacketStore.query(nativeDir, -1, true, "", 0, () -> false);
            require(nativeRecords.records.get(0).id == 1200, "跨段最后一包可读");
            File merged = new File(c.getCacheDir(), "merged_check.pcap");
            try (OutputStream out = new FileOutputStream(merged)) {
                PacketStore.export(nativeDir, out, "pcap");
            }
            File reread = dir(c, "native_roundtrip");
            try (InputStream in = new FileInputStream(merged)) {
                PacketStore.importPcap(in, reread, new JSONObject());
            }
            require(PacketStore.count(reread) == 1200, "跨段合并导出回读");
            log.append("7200 万字节原生写盘、64 MiB 分段、1200 包停止排空、跨段导出回读：通过\n");
            PacketFilter filter = new PacketFilter();
            filter.direction = 2; // PCAP 导入没有方向信息，实际索引值为 2。
            require(PacketStore.query(a, -1, filter, () -> false).records.size() == 128,
                "新分页128与未知方向筛选");
            filter.ids = new HashSet<>(Arrays.asList(1L, 175L));
            require(
                PacketStore.query(a, -1, filter, () -> false).records.size() == 2, "稀疏收藏查询");
            PacketNotes.edit(a, Collections.singleton(1L), false, "设备自检收藏");
            require(PacketNotes.read(a).get(1L).equals("设备自检收藏"), "收藏保存");
            PacketNotes.edit(a, Collections.singleton(1L), false, "改名");
            require(PacketNotes.read(a).get(1L).equals("改名"), "收藏原子替换");
            PacketNotes.edit(a, Collections.singleton(1L), true, null);
            require(
                PacketNotes.read(a).isEmpty() && PacketStore.count(a) == 175, "移除收藏保留原包");
            JSONObject quality = IntegrityReport.generate(a, PacketStore.manifest(a), () -> false);
            require(quality.getLong("recordsChecked") == 175
                    && quality.getJSONArray("errors").length() == 0,
                "完整性文件检查");
            File isolation = dir(c, "unknown_isolation");
            java.lang.Process isolated = new ProcessBuilder(
                core.getAbsolutePath(), "reliability-test", isolation.getAbsolutePath())
                                             .redirectErrorStream(true)
                                             .start();
            if (!isolated.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                isolated.destroyForcibly();
                throw new IOException("未知包写盘自检超时");
            }
            String isolatedLog;
            try (InputStream in = isolated.getInputStream();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                PacketStore.copy(in, bytes);
                isolatedLog = bytes.toString("UTF-8");
            }
            require(isolated.exitValue() == 0, "归属/隔离写盘自检：" + isolatedLog);
            File u = new File(isolation, "unattributed");
            require(PacketStore.count(isolation) == 10 && PacketStore.count(u) == 10,
                "主/未知索引独立");
            PacketStore.Query uq = PacketStore.query(u, -1, true, "", 0, () -> false);
            require(uq.records.size() == 10 && (uq.records.get(0).flags & 8) != 0
                    && uq.records.get(0).uid == -1,
                "未知标记");
            require(PacketStore.readText(new File(u, "attribution.jsonl")).contains("timeout"),
                "归属失败证据落盘");
            log.append("新分页、收藏改名/移除、完整性报告、归属依据与未知包独立写盘：通过\n");
            result.putString("stream", log.toString());
            finish(-1, result);
        } catch (Throwable e) {
            result.putString("stream", log + "失败：" + e.toString());
            finish(0, result);
        }
    }
    private void storageCheck(Context c, boolean granted, StringBuilder log) throws Exception {
        require(android.os.Environment.isExternalStorageManager() == granted, "存储授权状态");
        File source = dir(c, "export_source");
        byte[] data = "storage export fixture".getBytes(StandardCharsets.UTF_8);
        java.nio.file.Files.write(new File(source, "evidence.bin").toPath(), data);
        String name = "NetCap-storage-check-" + UUID.randomUUID() + ".zip";
        File own = new File(c.getExternalFilesDir(null), name);
        File root = android.os.Environment.getExternalStorageDirectory();
        File shared = new File(root, name);
        File folder = new File(root, "NetCap-storage-check-" + UUID.randomUUID());
        File nested = new File(folder, name);
        try {
            ExportFiles.save(own.getParentFile(), name, out -> PacketStore.export(source, out, "zip"));
            require(own.isFile() && own.length() > 0, "默认应用目录导出");
            if (!granted) {
                boolean denied = false;
                try {
                    ExportFiles.save(root, name, out -> PacketStore.export(source, out, "zip"));
                } catch (IOException e) {
                    denied = e.getMessage().contains("未生成可保留的临时文件");
                    log.append("共享根目录未授权：已正确拒绝，未谎报保留临时文件\n");
                }
                require(denied && !shared.exists(), "未授权共享目录写入失败提示");
            } else {
                ExportFiles.save(root, name, out -> PacketStore.export(source, out, "zip"));
                require(folder.mkdir(), "共享子目录创建");
                ExportFiles.save(folder, name, out -> PacketStore.export(source, out, "zip"));
                for (File zip : new File[] {shared, nested})
                    try (java.util.zip.ZipFile read = new java.util.zip.ZipFile(zip);
                         InputStream in = read.getInputStream(read.getEntry("evidence.bin"));
                         ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        PacketStore.copy(in, bytes);
                        require(Arrays.equals(data, bytes.toByteArray()), "共享位置 ZIP 回读");
                    }
                log.append("授权后内部存储根目录、共享子目录导出及 ZIP 原字节回读：通过\n");
            }
            log.append("默认应用目录导出：通过\n");
        } finally {
            for (File file : new File[] {own, shared, nested, folder})
                if (file.exists()) require(file.delete(), "清理自建测试文件：" + file);
        }
    }
}
