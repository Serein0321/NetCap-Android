package io.github.netcap.app;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.json.*;

/** 会话元信息与文件管理。分组只写标签；删除不追随符号链接、不越过注册根目录。 */
final class SessionLibrary {
    static final class Entry {
        File file;
        JSONObject meta;
        String title, target, group, description;
        long started, duration, packets, unknown, bytes;
    }
    static Entry read(File session) throws IOException {
        Entry e = new Entry();
        e.file = session;
        e.meta = PacketStore.manifest(session);
        e.target = e.meta.optString("label", session.getName());
        e.title = e.meta.optString("title", e.target);
        e.group = e.meta.optString("collection", "");
        e.description = e.meta.optString("description", "");
        e.started = e.meta.optLong("started", session.lastModified());
        e.duration = e.meta.optLong("captureDurationMillis");
        e.packets = PacketStore.count(session);
        e.unknown = PacketStore.count(new File(session, "unattributed"));
        final long[] size = {0};
        Files.walkFileTree(session.toPath(), new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path path, BasicFileAttributes a) {
                if (!a.isSymbolicLink())
                    size[0] += a.size();
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFileFailed(Path path, IOException ex) {
                return FileVisitResult.CONTINUE;
            }
        });
        e.bytes = size[0];
        return e;
    }
    static void sort(List<Entry> entries, int order) {
        entries.sort((a, b) -> {
            int c = order == 1 ? Long.compare(a.started, b.started)
                : order == 2   ? a.title.compareToIgnoreCase(b.title)
                : order == 3   ? Long.compare(b.packets, a.packets)
                : order == 4   ? Long.compare(b.bytes, a.bytes)
                               : Long.compare(b.started, a.started);
            return c != 0 ? c : a.file.getName().compareTo(b.file.getName());
        });
    }
    static void guard(File session, Collection<File> roots, File active) throws IOException {
        Path target = session.toPath().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(target.resolve("session.json"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("不是可管理的会话目录");
        IOException failure = null;
        for (File root : roots) try {
                WorkspaceFiles files = new WorkspaceFiles(root);
                files.guard(session, active, false);
                return;
            } catch (IOException e) {
                failure = e;
            }
        throw new IOException("会话不在可管理的保存目录中，或正在采集/位于其他会话内部", failure);
    }
    static void validLeaf(String name, int max) throws IOException {
        if (name == null || name.trim().isEmpty() || name.equals(".") || name.equals("..")
            || name.length() > max)
            throw new IOException("名称为空或过长");
        for (int i = 0; i < name.length(); i++)
            if (Character.isISOControl(name.charAt(i))
                || "\\/:*?\"<>|".indexOf(name.charAt(i)) >= 0)
                throw new IOException("名称包含不允许的字符");
    }
    static synchronized void annotate(File session, Collection<File> roots, File active,
        String title, String description, String group) throws Exception {
        guard(session, roots, active);
        JSONObject meta = new JSONObject(PacketStore.readText(new File(session, "session.json")));
        if (title != null) {
            if (title.trim().isEmpty() || title.length() > 100)
                throw new IOException("名称需为1–100字");
            meta.put("title", title.trim());
        }
        if (description != null) {
            if (description.length() > 500)
                throw new IOException("备注最多500字");
            meta.put("description", description.trim());
        }
        if (group != null) {
            if (group.length() > 48 || group.matches("(?s).*\\p{Cntrl}.*"))
                throw new IOException("分组名称无效");
            meta.put("collection", group.trim());
        }
        PacketStore.saveManifest(session, meta);
    }
    static synchronized void delete(File session, Collection<File> roots, File active)
        throws IOException {
        guard(session, roots, active);
        final Path directory = session.toPath();
        final Path marker = directory.resolve("session.json");
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
                if (!p.equals(marker))
                    Files.delete(p);
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult postVisitDirectory(Path p, IOException error)
                throws IOException {
                if (error != null)
                    throw error;
                if (p.equals(directory))
                    Files.deleteIfExists(marker);
                Files.delete(p);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
