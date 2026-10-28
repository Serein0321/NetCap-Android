package io.github.netcap.app;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Read-only ZIP namespace. No extraction, writes, key material or external application intents. */
final class ArchiveIndex {
    static final int MAX_ENTRIES = 10000, MAX_DIRECTORY = 8 * 1024 * 1024, PREVIEW = 65536;
    static final class Entry {
        String name, path;
        boolean directory;
        long size;
        Entry(String path, boolean directory, long size) {
            this.path = path;
            this.directory = directory;
            this.size = size;
            String p = directory ? path.substring(0, path.length() - 1) : path;
            name = p.substring(p.lastIndexOf('/') + 1);
        }
    }
    final File file;
    final long length, modified;
    ArchiveIndex(File file) throws IOException {
        this.file = file;
        length = file.length();
        modified = file.lastModified();
        validate(file);
    }
    static int u16(byte[] b, int n) {
        return (b[n] & 255) | ((b[n + 1] & 255) << 8);
    }
    static long u32(byte[] b, int n) {
        return u16(b, n) | ((long) u16(b, n + 2) << 16);
    }
    static void validate(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 22)
                throw new IOException("ZIP文件不完整");
            int size = (int) Math.min(in.length(), 65557);
            byte[] tail = new byte[size];
            in.seek(in.length() - size);
            in.readFully(tail);
            for (int p = size - 22; p >= 0; p--)
                if (u32(tail, p) == 0x06054b50L && p + 22 + u16(tail, p + 20) == size) {
                    long count = u16(tail, p + 10), directory = u32(tail, p + 12),
                         offset = u32(tail, p + 16);
                    if (u16(tail, p + 4) != 0 || u16(tail, p + 6) != 0 || u16(tail, p + 8) != count)
                        throw new IOException("不支持分卷ZIP");
                    if (count == 65535 || directory == 0xffffffffL || offset == 0xffffffffL)
                        throw new IOException("本版暂不支持ZIP64浏览");
                    if (count > MAX_ENTRIES || directory > MAX_DIRECTORY)
                        throw new IOException("压缩包目录过大，超过浏览上限");
                    if (offset + directory > in.length() - size + p)
                        throw new IOException("ZIP目录范围无效");
                    return;
                }
            throw new IOException("未找到完整ZIP目录");
        }
    }
    static boolean safe(String path) {
        if (path.isEmpty() || path.length() > 2048 || path.startsWith("/") || path.contains("\\")
            || path.contains(":"))
            return false;
        String[] parts = path.split("/", -1);
        for (int n = 0; n < parts.length; n++) {
            if (parts[n].equals(".") || parts[n].equals("..")
                || parts[n].isEmpty() && n < parts.length - 1)
                return false;
            for (int i = 0; i < parts[n].length(); i++)
                if (Character.isISOControl(parts[n].charAt(i)))
                    return false;
        }
        return true;
    }
    void stable() throws IOException {
        if (file.length() != length || file.lastModified() != modified)
            throw new IOException("压缩包已变化，请重新打开");
    }
    List<Entry> list(String prefix) throws IOException {
        stable();
        if (!prefix.isEmpty() && (!prefix.endsWith("/") || !safe(prefix)))
            throw new IOException("无效目录");
        Map<String, Entry> found = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            int count = 0;
            while (all.hasMoreElements()) {
                if (++count > MAX_ENTRIES)
                    throw new IOException("压缩包条目超出上限");
                ZipEntry e = all.nextElement();
                String name = e.getName();
                if (!safe(name))
                    throw new IOException("压缩包包含不安全或异常路径");
                if (!seen.add(name))
                    throw new IOException("压缩包包含重复路径，无法可靠浏览");
                if (!name.startsWith(prefix) || name.equals(prefix))
                    continue;
                String rest = name.substring(prefix.length());
                int slash = rest.indexOf('/');
                String path = slash >= 0 ? prefix + rest.substring(0, slash + 1) : name;
                boolean folder = slash >= 0 || e.isDirectory();
                Entry old = found.get(path);
                if (old == null)
                    found.put(path, new Entry(path, folder, folder ? -1 : e.getSize()));
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("ZIP文件名编码无法读取", e);
        }
        stable();
        List<Entry> out = new ArrayList<>(found.values());
        out.sort((a, b)
                     -> a.directory != b.directory ? (a.directory ? -1 : 1)
                                                   : a.name.compareToIgnoreCase(b.name));
        return out;
    }
    byte[] preview(String path) throws IOException {
        stable();
        if (!safe(path) || path.endsWith("/"))
            throw new IOException("不是可预览文件");
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null || entry.isDirectory())
                throw new IOException("文件已变化");
            try (InputStream in = zip.getInputStream(entry);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[8192];
                while (out.size() < PREVIEW) {
                    int n = in.read(b, 0, Math.min(b.length, PREVIEW - out.size()));
                    if (n < 0)
                        break;
                    out.write(b, 0, n);
                }
                stable();
                return out.toByteArray();
            }
        }
    }
}
