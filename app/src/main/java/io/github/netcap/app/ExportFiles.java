package io.github.netcap.app;

import java.io.*;
import java.nio.file.Files;

/** 实际创建文件验证写权限；失败信息只报告真正存在的临时数据。 */
final class ExportFiles {
    interface Writer {
        void write(OutputStream out) throws IOException;
    }
    static boolean contains(File root, File file) throws IOException {
        return root != null && file.getCanonicalFile().toPath().startsWith(
                                   root.getCanonicalFile().toPath());
    }
    static void checkWritable(File dir) throws IOException {
        if (!dir.isDirectory())
            throw new IOException("目录不存在或不可访问：" + dir);
        File probe = null;
        try {
            probe = File.createTempFile(".netcap-check-", ".tmp", dir);
            try (FileOutputStream out = new FileOutputStream(probe)) {
                out.write(0);
                out.getFD().sync();
            }
        } finally {
            if (probe != null)
                Files.deleteIfExists(probe.toPath());
        }
    }
    static File save(File dir, String name, Writer writer) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/")
            || name.contains("\\") || name.indexOf('\0') >= 0)
            throw new IOException("请输入有效的文件名");
        File partial = null;
        boolean complete = false;
        try {
            File target = new File(dir, name);
            if (target.exists())
                throw new IOException("同名文件已存在，请更换名称");
            checkWritable(dir);
            partial = File.createTempFile(".netcap-export-", ".partial", dir);
            try (FileOutputStream out = new FileOutputStream(partial)) {
                writer.write(out);
                out.getFD().sync();
            }
            complete = true;
            // 不替换已有文件，包含导出期间新出现的同名文件。
            Files.move(partial.toPath(), target.toPath());
            return target;
        } catch (IOException | SecurityException e) {
            String retained = "未生成可保留的临时文件。";
            if (partial != null && partial.isFile()) {
                if (partial.length() > 0)
                    retained = (complete ? "已生成的文件保留在：" : "临时文件（可能不完整）保留在：")
                        + partial.getAbsolutePath();
                else if (!partial.delete())
                    retained = "空临时文件未能清理：" + partial.getAbsolutePath();
            }
            throw new IOException("目标目录：" + dir.getAbsolutePath()
                    + "\n请确认已授予所有文件访问权限、目录可写且剩余空间充足。\n"
                    + retained + "\n原因：" + e.getMessage(), e);
        }
    }
}
