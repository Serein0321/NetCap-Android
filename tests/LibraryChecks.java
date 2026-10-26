package io.github.netcap.app;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
public final class LibraryChecks {
    static int checks;
    static void require(boolean ok, String message) throws Exception {
        checks++;
        if (!ok)
            throw new Exception(message);
    }
    interface Work {
        void run() throws Exception;
    }
    static void rejected(Work work, String message) throws Exception {
        boolean no = false;
        try {
            work.run();
        } catch (IOException e) {
            no = true;
        }
        require(no, message);
    }
    static File create(File root, String name, long started) throws Exception {
        File session = new File(root, name);
        require(session.mkdir(), "mkdir");
        PacketStore.saveManifest(
            session, new JSONObject().put("label", name).put("started", started).put("uid", 12345));
        Files.write(new File(session, "packets.idx").toPath(), new byte[128]);
        Files.write(new File(session, "capture_0001.pcap").toPath(), new byte[] {1, 2, 3});
        return session;
    }
    public static void main(String[] args) throws Exception {
        File work = new File(args[0]);
        work.mkdirs();
        File root = new File(work, "registered");
        root.mkdir();
        File outside = new File(work, "outside");
        outside.mkdir();
        File a = create(root, "Alpha", 100), b = create(root, "Beta", 200),
             foreign = create(outside, "Foreign", 300);
        List<File> roots = Collections.singletonList(root);
        byte[] raw = Files.readAllBytes(new File(a, "capture_0001.pcap").toPath());
        SessionLibrary.annotate(a, roots, null, "会话名称", "操作备注", "登录测试");
        SessionLibrary.Entry entry = SessionLibrary.read(a);
        require(entry.title.equals("会话名称") && entry.target.equals("Alpha"),
            "display title preserves capture target");
        require(entry.group.equals("登录测试") && entry.description.equals("操作备注"),
            "group and note");
        require(entry.started == 100 && entry.packets == 2, "metadata/count");
        require(Arrays.equals(raw, Files.readAllBytes(new File(a, "capture_0001.pcap").toPath())),
            "annotations never modify PCAP");
        SessionLibrary.annotate(a, roots, null, "A title", null, "");
        require(SessionLibrary.read(a).group.isEmpty(), "ungroup");
        List<SessionLibrary.Entry> entries =
            new ArrayList<>(Arrays.asList(SessionLibrary.read(a), SessionLibrary.read(b)));
        SessionLibrary.sort(entries, 0);
        require(entries.get(0).file.equals(b), "newest first");
        SessionLibrary.sort(entries, 1);
        require(entries.get(0).file.equals(a), "oldest first");
        SessionLibrary.sort(entries, 2);
        require(entries.get(0).file.equals(a), "name sorting");
        rejected(() -> SessionLibrary.delete(a, roots, a), "active cannot delete");
        require(a.isDirectory(), "active data retained");
        rejected(()
                     -> SessionLibrary.annotate(a, roots, a, "bad", null, null),
            "active cannot annotate");
        File unknown = new File(a, "unattributed");
        unknown.mkdir();
        Files.write(new File(unknown, "unknown.pcap").toPath(), new byte[] {4, 5});
        rejected(() -> SessionLibrary.delete(a, roots, unknown), "active child protects parent");
        rejected(() -> SessionLibrary.delete(root, roots, null), "cannot delete registered root");
        rejected(
            () -> SessionLibrary.delete(foreign, roots, null), "cannot delete foreign session");
        File ordinary = new File(root, "ordinary");
        ordinary.mkdir();
        Files.write(new File(ordinary, "keep.txt").toPath(), new byte[] {9});
        rejected(
            () -> SessionLibrary.delete(ordinary, roots, null), "unmarked directory cannot delete");
        rejected(() -> SessionLibrary.validLeaf("../escape", 180), "path traversal name");
        rejected(() -> SessionLibrary.validLeaf("..", 180), "dotdot");
        rejected(() -> SessionLibrary.validLeaf("a\\b", 180), "backslash");
        rejected(() -> SessionLibrary.validLeaf("a\nb", 180), "control");
        SessionLibrary.validLeaf("抓包记录_01", 40);
        checks++;
        File broken = create(root, "Broken", 400);
        Files.write(new File(broken, "session.json").toPath(), "invalid json".getBytes("UTF-8"));
        boolean refused = false;
        try {
            SessionLibrary.annotate(broken, roots, null, "replace", null, null);
        } catch (Exception e) {
            refused = true;
        }
        require(refused
                && new String(
                    Files.readAllBytes(new File(broken, "session.json").toPath()), "UTF-8")
                    .equals("invalid json"),
            "bad manifest cannot be silently overwritten");
        boolean linkTest = false;
        try {
            Path link = new File(root, "link").toPath();
            Files.createSymbolicLink(link, foreign.toPath());
            rejected(()
                         -> SessionLibrary.delete(link.toFile(), roots, null),
                "linked session cannot delete target");
            Files.createSymbolicLink(new File(a, "external-link").toPath(), foreign.toPath());
            linkTest = true;
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            System.out.println("SKIP symlink creation: platform privilege unavailable");
        }
        SessionLibrary.delete(a, roots, null);
        require(!a.exists(), "physical recursive deletion");
        require(new File(foreign, "session.json").exists(), "external directory survives");
        require(
            new File(ordinary, "keep.txt").exists() && b.isDirectory(), "unselected files survive");
        SessionLibrary.delete(broken, roots, null);
        require(!broken.exists(), "corrupt marked session can explicitly delete");
        // Exercise unusual asymmetric camera/nav/IME insets, short landscape and normal portrait.
        for (int w : new int[] {320, 360, 800, 1080, 2400})
            for (int h : new int[] {180, 320, 800, 1080})
                for (int left : new int[] {0, 44, 104})
                    for (int right : new int[] {0, 24, 88})
                        for (int ime : new int[] {0, 90, 140}) {
                            int top = 20, bottom = 16;
                            ModalGeometry f = ModalGeometry.fit(
                                w, h, left, top, right, bottom, ime, 8, 2000, 2000);
                            require(f.x >= left && f.y >= top, "top/left within safe area");
                            require(f.x + f.width <= w - right
                                    && f.y + f.height <= h - Math.max(bottom, ime),
                                "right/bottom within safe area");
                            require(f.width > 0 && f.height > 0, "positive viewport");
                        }
        ModalGeometry k80 = ModalGeometry.fit(2400, 1080, 122, 0, 0, 48, 0, 16, 2000, 1200);
        require(k80.x >= 138 && k80.x + k80.width <= 2384, "K80 landscape safe centering");
        System.out.println(
            "PASS " + checks + " library/geometry assertions; symlink guard executed=" + linkTest);
    }
}
