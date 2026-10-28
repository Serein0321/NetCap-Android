package io.github.netcap.app;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/**
 * Actual filesystem workspace. Never follows links, overwrites a destination or edits session
 * internals.
 */
final class WorkspaceFiles {
    static final String STAGING = ".netcap-txn-";
    static final int MAX_ENTRIES = 200000;
    final Path root, alias;
    interface Cancel {
        boolean cancelled();
    }
    interface Progress {
        void update(String value);
    }
    static final class Item {
        File file;
        boolean directory, session, link;
        long size, modified, packets;
        String description = "";
    }
    static final class Result {
        final Map<File, File> moved = new LinkedHashMap<>();
        final List<File> copied = new ArrayList<>(), deleted = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
    }
    WorkspaceFiles(File base) throws IOException {
        alias = base.toPath().toAbsolutePath().normalize();
        root = base.getCanonicalFile().toPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("保存目录不存在或无法访问");
    }
    Path path(File file, boolean allowRoot) throws IOException {
        Path p = file.toPath().toAbsolutePath().normalize();
        if (!p.startsWith(root) && p.startsWith(alias))
            p = root.resolve(alias.relativize(p));
        if (!p.startsWith(root) || !allowRoot && p.equals(root))
            throw new IOException("不能操作保存根目录或目录外的文件");
        Path at = root;
        for (Path part : root.relativize(p)) {
            at = at.resolve(part);
            if (Files.isSymbolicLink(at))
                throw new IOException("不支持符号链接，请使用保存目录内的实际文件");
        }
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("文件已不存在：" + p.getFileName());
        return p;
    }
    static boolean session(Path p) {
        return Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
            && Files.isRegularFile(p.resolve("session.json"), LinkOption.NOFOLLOW_LINKS);
    }
    Path sessionOwner(Path p) {
        Path owner = null;
        for (Path at = p; at != null && at.startsWith(root); at = at.getParent())
            if (session(at))
                owner = at;
        return owner;
    }
    void guard(File file, File active, boolean destination) throws IOException {
        Path p = path(file, destination);
        if (active != null) {
            Path live = active.getCanonicalFile().toPath();
            if (p.startsWith(live) || !destination && live.startsWith(p))
                throw new IOException("正在抓取的会话及其父目录不能修改");
        }
        Path owner = sessionOwner(p);
        if (owner != null && (destination || !owner.equals(p)))
            throw new IOException("会话内部文件只读，请管理整个会话目录");
    }
    static void name(String name) throws IOException {
        SessionLibrary.validLeaf(name, 180);
        if (name.endsWith(".") || name.endsWith(" ")
            || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 240
            || name.startsWith(STAGING))
            throw new IOException("文件名过长、结尾无效或使用了保留名称");
    }
    List<Item> list(File folder, int order) throws IOException {
        Path dir = path(folder, true);
        if (!Files.isDirectory(dir))
            throw new IOException("不是文件夹");
        List<Item> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (out.size() >= 10000)
                    throw new IOException("单层超过10000项，请整理后再浏览");
                BasicFileAttributes a =
                    Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                Item e = new Item();
                e.file = p.toFile();
                e.link = a.isSymbolicLink();
                e.directory = a.isDirectory();
                e.session = !e.link && session(p);
                e.modified = a.lastModifiedTime().toMillis();
                e.size = a.isRegularFile() ? a.size() : 0;
                if (e.session) {
                    org.json.JSONObject m = PacketStore.manifest(e.file);
                    e.packets = PacketStore.count(e.file);
                    e.description = m.optString("title", m.optString("label", "抓包会话")) + " · "
                        + e.packets + " 包";
                } else
                    e.description = p.getFileName().toString().startsWith(STAGING)
                        ? "未完成复制 · 临时文件夹"
                        : e.link      ? "链接 · 不进入"
                        : e.directory ? "文件夹"
                                      : PacketStore.formatBytes(e.size);
                out.add(e);
            }
        }
        out.sort((a, b) -> {
            if (a.directory != b.directory)
                return a.directory ? -1 : 1;
            int n = order == 1 ? Long.compare(a.modified, b.modified)
                : order == 2   ? a.file.getName().compareToIgnoreCase(b.file.getName())
                : order == 3   ? Long.compare(b.size, a.size)
                               : Long.compare(b.modified, a.modified);
            return n != 0 ? n : a.file.getName().compareTo(b.file.getName());
        });
        return out;
    }
    File mkdir(File parent, String leaf, File active) throws IOException {
        name(leaf);
        guard(parent, active, true);
        Path dest = path(parent, true).resolve(leaf);
        Files.createDirectory(dest);
        return dest.toFile();
    }
    File rename(File file, String leaf, File active) throws IOException {
        name(leaf);
        guard(file, active, false);
        Path source = path(file, false), dest = source.resolveSibling(leaf);
        if (source.equals(dest))
            return source.toFile();
        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("已存在同名文件");
        Files.move(source, dest);
        return dest.toFile();
    }
    List<Path> sources(Collection<File> files, File active) throws IOException {
        return sources(files, active, false);
    }
    List<Path> sources(Collection<File> files, File active, boolean copy) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (File f : files) {
            Path p = path(f, false);
            if (copy) {
                if (active != null) {
                    Path live = active.getCanonicalFile().toPath();
                    if (p.startsWith(live) || live.startsWith(p))
                        throw new IOException("正在采集的文件不能复制");
                }
            } else
                guard(f, active, false);
            if (!paths.contains(p))
                paths.add(p);
        }
        paths.sort(Comparator.comparingInt(Path::getNameCount));
        List<Path> minimal = new ArrayList<>();
        for (Path p : paths) {
            boolean covered = false;
            for (Path parent : minimal)
                if (p.startsWith(parent)) {
                    covered = true;
                    break;
                }
            if (!covered)
                minimal.add(p);
        }
        if (minimal.isEmpty())
            throw new IOException("请先选择文件或文件夹");
        return minimal;
    }
    static void check(Cancel cancel) throws IOException {
        if (cancel.cancelled() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("已取消；未提交的副本已清理");
    }
    static final class Plan {
        long bytes;
        int entries;
    }
    Plan inspect(Path path, Cancel cancel) throws IOException {
        Plan plan = new Plan();
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            void count() throws IOException {
                check(cancel);
                if (++plan.entries > MAX_ENTRIES)
                    throw new IOException("单次操作超过200000项");
            }
            public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a)
                throws IOException {
                count();
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
                count();
                if (a.isSymbolicLink() || !a.isRegularFile())
                    throw new IOException("不支持链接或特殊文件：" + p.getFileName());
                if (Long.MAX_VALUE - plan.bytes < a.size())
                    throw new IOException("文件总量超出范围");
                plan.bytes += a.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return plan;
    }
    Path unique(Path directory, String leaf, boolean folder, Set<Path> reserved)
        throws IOException {
        Path dest = directory.resolve(leaf);
        if (!Files.exists(dest, LinkOption.NOFOLLOW_LINKS) && !reserved.contains(dest))
            return dest;
        String stem = leaf, ext = "";
        int dot = leaf.lastIndexOf('.');
        if (!folder && dot > 0) {
            stem = leaf.substring(0, dot);
            ext = leaf.substring(dot);
        }
        for (int i = 1; i <= 9999; i++) {
            String suffix = " (副本" + (i == 1 ? "" : i) + ")" + ext, base = stem;
            while (!base.isEmpty()
                && (base + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 240)
                base = base.substring(0, base.offsetByCodePoints(base.length(), -1));
            String candidate = base + suffix;
            name(candidate);
            dest = directory.resolve(candidate);
            if (!Files.exists(dest, LinkOption.NOFOLLOW_LINKS) && !reserved.contains(dest))
                return dest;
        }
        throw new IOException("同名副本过多");
    }
    Result transfer(Collection<File> files, File directory, boolean move, File active,
        Cancel cancel, Progress progress) throws Exception {
        guard(directory, active, true);
        Path parent = path(directory, true);
        if (!Files.isDirectory(parent))
            throw new IOException("目标不是文件夹");
        List<Path> inputs = sources(files, active, !move), outputs = new ArrayList<>();
        Set<Path> reserved = new HashSet<>();
        long size = 0;
        int entries = 0;
        for (Path p : inputs) {
            if (parent.startsWith(p))
                throw new IOException("不能把文件夹放进自己或自己的子目录");
            if (move && parent.equals(p.getParent()))
                throw new IOException("所选项目已经在此目录");
            Plan plan = inspect(p, cancel);
            size = Math.addExact(size, plan.bytes);
            entries += plan.entries;
            if (entries > MAX_ENTRIES)
                throw new IOException("单次操作项数过多");
            Path dest = unique(parent, p.getFileName().toString(), Files.isDirectory(p), reserved);
            reserved.add(dest);
            outputs.add(dest);
        }
        Result result = new Result();
        if (move) {
            for (int i = 0; i < inputs.size(); i++) {
                if (cancel.cancelled()) {
                    result.failures.add("已取消；此前完成的移动已保留");
                    break;
                }
                Path from = inputs.get(i), to = outputs.get(i);
                progress.update("移动：" + from.getFileName());
                try {
                    guard(from.toFile(), active, false);
                    Files.move(from, to);
                    result.moved.put(from.toFile(), to.toFile());
                } catch (IOException e) {
                    result.failures.add(from.getFileName() + "：" + e.getMessage());
                }
            }
            return result;
        }
        long free = parent.toFile().getUsableSpace();
        if (free > 0 && (size > free || free - size < 16 * 1024 * 1024))
            throw new IOException("目标剩余空间不足");
        Path stage = parent.resolve(STAGING + UUID.randomUUID());
        Files.createDirectory(stage);
        try {
            for (int i = 0; i < inputs.size(); i++) {
                check(cancel);
                copyTree(inputs.get(i), stage.resolve(Integer.toString(i)), cancel, progress);
            }
            check(cancel);
            // Commit complete copies only. No replace option: an externally-created collision fails
            // safely.
            for (int i = 0; i < inputs.size(); i++) {
                Path staged = stage.resolve(Integer.toString(i)), to = outputs.get(i);
                try {
                    Files.move(staged, to);
                    result.copied.add(to.toFile());
                } catch (IOException e) {
                    result.failures.add(inputs.get(i).getFileName() + "：" + e.getMessage());
                }
            }
            return result;
        } finally {
            erase(stage);
        }
    }
    static void copyTree(Path from, Path to, Cancel cancel, Progress progress) throws Exception {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a)
                throws IOException {
                check(cancel);
                Files.createDirectory(to.resolve(from.relativize(p)));
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
                check(cancel);
                if (a.isSymbolicLink() || !a.isRegularFile())
                    throw new IOException("文件类型在复制时变化");
                Path target = to.resolve(from.relativize(p));
                progress.update("复制并校验：" + p.getFileName());
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    long count = 0;
                    try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS);
                        FileOutputStream out = new FileOutputStream(target.toFile())) {
                        byte[] b = new byte[131072];
                        int n;
                        while ((n = in.read(b)) != -1) {
                            check(cancel);
                            out.write(b, 0, n);
                            md.update(b, 0, n);
                            count += n;
                        }
                        out.getFD().sync();
                    }
                    byte[] digest = md.digest();
                    if (count != a.size() || !Arrays.equals(digest, hash(p, cancel))
                        || !Arrays.equals(digest, hash(target, cancel)))
                        throw new IOException("文件在复制时发生变化或校验失败");
                    try {
                        Files.setLastModifiedTime(target, a.lastModifiedTime());
                    } catch (IOException ignored) {
                    }
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException(e);
                }
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult postVisitDirectory(Path p, IOException error)
                throws IOException {
                if (error != null)
                    throw error;
                try {
                    Files.setLastModifiedTime(
                        to.resolve(from.relativize(p)), Files.getLastModifiedTime(p));
                } catch (IOException ignored) {
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
    static byte[] hash(Path path, Cancel cancel) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] b = new byte[131072];
            int n;
            while ((n = in.read(b)) != -1) {
                check(cancel);
                md.update(b, 0, n);
            }
            return md.digest();
        }
    }
    static void erase(Path p) throws IOException {
        erase(p, null);
    }
    static void erase(Path p, Cancel cancel) throws IOException {
        final Path marker = Files.isDirectory(p) ? p.resolve("session.json") : null;
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            void interrupted() throws IOException {
                if (cancel != null)
                    check(cancel);
            }
            public FileVisitResult preVisitDirectory(Path f, BasicFileAttributes a)
                throws IOException {
                interrupted();
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                interrupted();
                if (!f.equals(marker))
                    Files.delete(f);
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult postVisitDirectory(Path f, IOException e) throws IOException {
                if (e != null)
                    throw e;
                interrupted();
                if (f.equals(p) && marker != null)
                    Files.deleteIfExists(marker);
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    static Path canonical(File file) {
        try {
            return file.getCanonicalFile().toPath();
        } catch (IOException e) {
            return file.toPath().toAbsolutePath().normalize();
        }
    }
    static boolean contains(File parent, File child) {
        return parent != null && child != null && canonical(child).startsWith(canonical(parent));
    }
    static File remap(File file, File from, File to) {
        if (file == null)
            return null;
        Path p = canonical(file), a = canonical(from);
        return p.startsWith(a) ? canonical(to).resolve(a.relativize(p)).toFile() : file;
    }
    Result delete(Collection<File> files, File active, Cancel cancel, Progress progress)
        throws Exception {
        List<Path> inputs = sources(files, active);
        for (Path p : inputs) inspect(p, cancel);
        Result result = new Result();
        for (Path p : inputs) {
            if (cancel.cancelled()) {
                result.failures.add("已取消；此前完成的删除不会恢复");
                break;
            }
            progress.update("删除：" + p.getFileName());
            try {
                guard(p.toFile(), active, false);
                erase(p, cancel);
                result.deleted.add(p.toFile());
            } catch (IOException e) {
                result.failures.add(p.getFileName() + "：" + e.getMessage() + "（可能已部分删除）");
            }
        }
        return result;
    }
}
