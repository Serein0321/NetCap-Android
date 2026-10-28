package io.github.netcap.app;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
public final class ArchiveChecks {
    static int checks;
    interface Job {
        void run() throws Exception;
    }
    static void need(boolean ok, String msg) throws Exception {
        checks++;
        if (!ok)
            throw new Exception(msg);
    }
    static void reject(Job job, String msg) throws Exception {
        boolean no = false;
        try {
            job.run();
        } catch (IOException e) {
            no = true;
        }
        need(no, msg);
    }
    static File zip(File root, String file, Map<String, byte[]> entries) throws Exception {
        File out = new File(root, file);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return out;
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        root.mkdirs();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("抓包/会话/readme.txt", "示例文本".getBytes("UTF-8"));
        entries.put("抓包/packets.pcap", new byte[] {1, 2, 3, 4});
        entries.put("empty/", new byte[0]);
        byte[] bomb = new byte[2 * 1024 * 1024];
        Arrays.fill(bomb, (byte) 'A');
        entries.put("large.txt", bomb);
        File file = zip(root, "archive.zip", entries);
        byte[] before = Files.readAllBytes(file.toPath());
        ArchiveIndex index = new ArchiveIndex(file);
        List<ArchiveIndex.Entry> first = index.list("");
        need(first.size() == 3, "implicit folders and regular files");
        need(first.get(0).directory && first.get(1).directory, "folders sorted first");
        need(index.list("抓包/").size() == 2, "nested virtual namespace");
        need(index.list("抓包/会话/").size() == 1, "deep folder");
        need(index.list("empty/").isEmpty(), "empty folder");
        need(new String(index.preview("抓包/会话/readme.txt"), "UTF-8").equals("示例文本"),
            "UTF8 names and content");
        need(index.preview("large.txt").length == 65536, "compressed large payload is bounded");
        need(Arrays.equals(before, Files.readAllBytes(file.toPath())),
            "browsing never alters archive");
        for (String path : new String[] {"../outside", "/absolute", "dir/../escape", "C:/x",
                 "dir\\x", "a//b", "./x", "a/./b", "x\n.txt"}) {
            need(!ArchiveIndex.safe(path), "unsafe virtual path rejected " + path);
            Map<String, byte[]> bad = new LinkedHashMap<>();
            bad.put(path, new byte[] {1});
            File f = zip(root, "bad-" + checks + ".zip", bad);
            reject(() -> new ArchiveIndex(f).list(""), "malformed entry never exposed");
        }
        reject(() -> index.preview("../outside"), "preview traversal rejected");
        reject(() -> index.preview("empty/"), "folder preview rejected");
        File truncated = new File(root, "truncated.zip");
        Files.write(truncated.toPath(), Arrays.copyOf(before, before.length - 8));
        reject(() -> new ArchiveIndex(truncated), "truncated footer");
        Map<String, byte[]> duplicate = new LinkedHashMap<>();
        duplicate.put("a.txt", new byte[] {1});
        duplicate.put("b.txt", new byte[] {2});
        File dup = zip(root, "duplicate.zip", duplicate);
        byte[] bytes = Files.readAllBytes(dup.toPath());
        for (int i = 0; i + 5 <= bytes.length; i++)
            if (bytes[i] == 'b' && bytes[i + 1] == '.' && bytes[i + 2] == 't' && bytes[i + 3] == 'x'
                && bytes[i + 4] == 't')
                bytes[i] = 'a';
        Files.write(dup.toPath(), bytes);
        reject(() -> new ArchiveIndex(dup).list(""), "duplicate names rejected");
        File excessive = new File(root, "excessive.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(excessive))) {
            for (int i = 0; i <= 10000; i++) {
                z.putNextEntry(new ZipEntry("e" + i));
                z.closeEntry();
            }
        }
        reject(
            () -> new ArchiveIndex(excessive), "entry count cap before loading central directory");
        byte[] zip64 = before.clone();
        zip64[zip64.length - 12] = (byte) 255;
        zip64[zip64.length - 11] = (byte) 255;
        File z64 = new File(root, "zip64.zip");
        Files.write(z64.toPath(), zip64);
        reject(() -> new ArchiveIndex(z64), "ZIP64 count marker");
        File changed = new File(root, "changed.zip");
        Files.write(changed.toPath(), before);
        ArchiveIndex stable = new ArchiveIndex(changed);
        Files.write(changed.toPath(), new byte[] {1}, StandardOpenOption.APPEND);
        reject(() -> stable.list(""), "archive replacement detected");
        File empty = zip(root, "empty.zip", Collections.emptyMap());
        need(new ArchiveIndex(empty).list("").isEmpty(), "empty archive valid");
        need(root.listFiles().length > 0 && root.getParentFile().listFiles().length > 0,
            "all outputs stayed in test workspace");
        System.out.println("PASS " + checks
            + (" archive assertions; read-only nested ZIP, UTF8 names, bounded preview, "
               + "path/duplicate/count/truncation/replacement checks"));
    }
}
