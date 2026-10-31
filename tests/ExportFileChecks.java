package io.github.netcap.app;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** 真实文件导出成功、失败恢复和同名文件保护，不代替 Android 存储授权测试。 */
public final class ExportFileChecks {
    private static int checks;
    private static void need(boolean ok, String message) throws Exception {
        checks++;
        if (!ok) throw new Exception(message);
    }
    private static IOException failure(File dir, String name, ExportFiles.Writer writer)
        throws Exception {
        try {
            ExportFiles.save(dir, name, writer);
            throw new Exception("应拒绝导出：" + name);
        } catch (IOException e) {
            return e;
        }
    }
    public static void main(String[] args) throws Exception {
        File work = new File(args[0]);
        Files.createDirectories(work.toPath());
        File source = new File(work, "source"), destination = new File(work, "共享目录");
        source.mkdir();
        destination.mkdir();
        byte[] data = "完整会话原始数据".getBytes("UTF-8");
        Files.write(new File(source, "evidence.bin").toPath(), data);
        ExportFiles.checkWritable(destination);
        need(destination.list().length == 0, "目录检查不残留文件");
        need(ExportFiles.contains(work, destination), "子目录归属");
        need(!ExportFiles.contains(source, new File(work, "source-other")), "同前缀不是子目录");
        need(!ExportFiles.contains(source, new File(source, "../outside")), "标准化路径边界");
        File zip = ExportFiles.save(destination, "会话.zip",
            out -> PacketStore.export(source, out, "zip"));
        try (ZipFile read = new ZipFile(zip);
             InputStream in = read.getInputStream(read.getEntry("evidence.bin"));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PacketStore.copy(in, bytes);
            need(Arrays.equals(data, bytes.toByteArray()), "ZIP 内容完整可回读");
        }
        need(Arrays.equals(data, Files.readAllBytes(new File(source, "evidence.bin").toPath())),
            "导出保留源文件");
        byte[] zipBytes = Files.readAllBytes(zip.toPath());
        failure(destination, "会话.zip", out -> { throw new IOException("不能执行覆盖"); });
        need(Arrays.equals(zipBytes, Files.readAllBytes(zip.toPath())), "原有文件未覆盖");
        File late = new File(destination, "late.zip");
        IOException conflict = failure(destination, late.getName(), out -> {
            out.write(data);
            Files.write(late.toPath(), new byte[] {7});
        });
        need(Arrays.equals(new byte[] {7}, Files.readAllBytes(late.toPath())),
            "导出过程中出现同名文件也不覆盖");
        need(conflict.getMessage().contains("已生成的文件保留在："), "最终提交失败保留完整数据");
        IOException interrupted = failure(destination, "failed.zip", out -> {
            out.write(data);
            throw new IOException("模拟空间不足");
        });
        need(interrupted.getMessage().contains("可能不完整"), "中断文件不冒充完整导出");
        File[] retained = destination.listFiles((d, n) -> n.endsWith(".partial"));
        need(retained != null && retained.length == 2, "仅保留实际有内容的失败文件");
        for (File partial : retained)
            need(Arrays.equals(data, Files.readAllBytes(partial.toPath())), "保留字节与已写入数据一致");
        IOException empty = failure(destination, "empty.zip", out -> { throw new IOException("EPERM"); });
        need(empty.getMessage().contains("未生成可保留的临时文件"), "未写入不谎报保留文件");
        need(destination.listFiles((d, n) -> n.endsWith(".partial")).length == 2,
            "空临时文件清理");
        IOException missing = failure(new File(work, "missing"), "a.zip", out -> out.write(1));
        need(missing.getMessage().contains("未生成可保留的临时文件"), "不可访问目录的准确报错");
        failure(destination, "../escape.zip", out -> out.write(1));
        need(!new File(work, "escape.zip").exists(), "拒绝文件名跨目录");
        need(destination.listFiles((d, n) -> n.endsWith(".tmp")).length == 0, "不残留权限探测文件");
        System.out.println("Export file checks passed: " + checks);
    }
}
