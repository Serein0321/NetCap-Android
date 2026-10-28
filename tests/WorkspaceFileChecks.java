package io.github.netcap.app;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** Real synthetic filesystem operations, never user capture directories. */
public final class WorkspaceFileChecks {
    static int checks;
    static File root;
    static WorkspaceFiles fs;
    interface Action {
        void run() throws Exception;
    }
    static void need(boolean ok, String text) throws Exception {
        checks++;
        if (!ok)
            throw new Exception(text);
    }
    static void reject(Action action, String text) throws Exception {
        boolean no = false;
        try {
            action.run();
        } catch (Exception e) {
            no = true;
        }
        need(no, text);
    }
    static File write(File parent, String name, String value) throws Exception {
        File f = new File(parent, name);
        Files.write(f.toPath(), value.getBytes("UTF-8"));
        return f;
    }
    static WorkspaceFiles.Cancel never = () -> false;
    static WorkspaceFiles.Progress quiet = s -> {};
    public static void main(String[] args) throws Exception {
        File work = new File(args[0]);
        work.mkdirs();
        root = new File(work, "root");
        root.mkdir();
        File outside = new File(work, "outside");
        outside.mkdir();
        fs = new WorkspaceFiles(root);
        File a = fs.mkdir(root, "分组A", null), b = fs.mkdir(a, "子分组", null),
             dest = fs.mkdir(root, "目标", null);
        need(b.isDirectory(), "real nested directories");
        File note = write(a, "说明.txt", "test file");
        write(outside, "keep.txt", "outside preserved");
        reject(() -> fs.mkdir(root, "../escape", null), "traversal name");
        reject(() -> fs.mkdir(root, "x/y", null), "nested leaf");
        reject(() -> fs.mkdir(root, ".", null), "dot root");
        reject(() -> fs.mkdir(root, "bad ", null), "trailing space");
        reject(() -> fs.mkdir(outside, "escape", null), "foreign destination");
        reject(() -> fs.rename(root, "new-root", null), "root rename blocked");
        WorkspaceFiles.Result copy =
            fs.transfer(Collections.singletonList(note), dest, false, null, never, quiet);
        need(copy.copied.size() == 1 && note.exists(), "copy preserves original");
        need(Arrays.equals(Files.readAllBytes(note.toPath()),
                 Files.readAllBytes(copy.copied.get(0).toPath())),
            "copied bytes match");
        copy = fs.transfer(Collections.singletonList(note), dest, false, null, never, quiet);
        need(copy.copied.get(0).getName().contains("副本"), "collision keeps both");
        File renamed = fs.rename(note, "新名称.txt", null);
        need(!note.exists() && renamed.isFile(), "physical rename");
        reject(() -> fs.rename(renamed, "子分组", null), "rename never replaces directory");
        WorkspaceFiles.Result moved =
            fs.transfer(Collections.singletonList(renamed), b, true, null, never, quiet);
        File newNote = moved.moved.get(renamed);
        need(newNote.exists() && !renamed.exists(), "move removes old source");
        reject(()
                   -> fs.transfer(Collections.singletonList(a), b, false, null, never, quiet),
            "copy folder into child rejected");
        reject(()
                   -> fs.transfer(Collections.singletonList(root), dest, false, null, never, quiet),
            "root copy rejected");
        reject(()
                   -> fs.transfer(
                       Collections.singletonList(newNote), outside, false, null, never, quiet),
            "copy outside root rejected");
        reject(()
                   -> fs.transfer(Collections.singletonList(newNote), b, true, null, never, quiet),
            "same parent move rejected");
        File deep = fs.mkdir(b, "更深一层", null);
        write(deep, "payload.bin", "nested bytes");
        copy = fs.transfer(Arrays.asList(a, deep), dest, false, null, never, quiet);
        File cloned = copy.copied.get(0);
        need(copy.copied.size() == 1 && new File(cloned, "子分组/更深一层/payload.bin").isFile(),
            "ancestor selection de-duplicated");
        WorkbenchChecks.root = root;
        File session = WorkbenchChecks.session("session",
            Arrays.asList(WorkbenchChecks.tcp(100, 2, new byte[0]),
                WorkbenchChecks.tcp(101, 24, new byte[] {1, 2, 3})));
        PacketNotes.edit(session, Collections.singleton(1L), false, "saved alias");
        String rawBefore = WorkbenchChecks.digest(PacketStore.segment(session, 1));
        reject(()
                   -> fs.rename(new File(session, "packets.idx"), "broken.idx", null),
            "session internals rename blocked");
        reject(()
                   -> fs.delete(Collections.singletonList(new File(session, "session.json")), null,
                       never, quiet),
            "session marker delete blocked");
        reject(() -> fs.mkdir(session, "bad", null), "session destination read-only");
        reject(()
                   -> fs.transfer(
                       Collections.singletonList(session), dest, false, session, never, quiet),
            "active copy blocked");
        reject(() -> fs.rename(session, "active", session), "active rename blocked");
        reject(()
                   -> fs.delete(Collections.singletonList(root), session, never, quiet),
            "root/active ancestor protected");
        File group = fs.mkdir(root, "会话分组", null);
        moved = fs.transfer(Collections.singletonList(session), group, true, null, never, quiet);
        File relocated = moved.moved.get(session);
        need(!session.exists() && relocated.isDirectory(), "whole session moved");
        need(PacketStore.count(relocated) == 2
                && PacketNotes.read(relocated).get(1L).equals("saved alias"),
            "index and notes remain valid");
        need(WorkbenchChecks.digest(PacketStore.segment(relocated, 1)).equals(rawBefore),
            "original PCAP unchanged");
        need(PacketStore.query(relocated, -1, new PacketFilter(), never::cancelled).records.size()
                == 2,
            "moved session query works");
        SessionLibrary.annotate(
            relocated, Collections.singletonList(root), null, "nested title", null, null);
        need(SessionLibrary.read(relocated).title.equals("nested title"),
            "nested session metadata allowed");
        reject(
            () -> fs.rename(group, "active-parent", relocated), "active session ancestor blocked");
        reject(()
                   -> fs.transfer(
                       Collections.singletonList(group), dest, true, relocated, never, quiet),
            "move active ancestor blocked");
        need(WorkspaceFiles.remap(new File(session, "unattributed"), session, relocated)
                 .equals(new File(relocated, "unattributed")),
            "unknown selected path remapped");
        need(WorkspaceFiles.remap(note, session, relocated).equals(note),
            "unrelated path unchanged");
        copy = fs.transfer(Collections.singletonList(relocated), dest, false, null, never, quiet);
        need(PacketStore.count(copy.copied.get(0)) == 2, "copied session query intact");
        copy = fs.transfer(Collections.singletonList(PacketStore.segment(relocated, 1)), dest,
            false, null, never, quiet);
        need(copy.copied.get(0).isFile(), "stopped session raw file copy permitted");
        File cancelFile =
            write(root, "cancel.bin", String.join("", Collections.nCopies(50000, "0123456789")));
        AtomicInteger calls = new AtomicInteger();
        reject(()
                   -> fs.transfer(Collections.singletonList(cancelFile), dest, false, null,
                       () -> calls.incrementAndGet() > 8, quiet),
            "copy cancellation");
        need(cancelFile.exists() && !new File(dest, "cancel.bin").exists(),
            "cancelled copy not published");
        need(Arrays.stream(dest.list()).noneMatch(n -> n.startsWith(WorkspaceFiles.STAGING)),
            "cancel staging removed");
        File one = write(root, "one.txt", "1"), two = write(root, "two.txt", "2");
        AtomicBoolean stop = new AtomicBoolean();
        moved = fs.transfer(
            Arrays.asList(one, two), dest, true, null, stop::get, msg -> stop.set(true));
        need(moved.moved.size() == 1 && !moved.failures.isEmpty(),
            "partial move retains completed mapping on cancel");
        need(two.exists(), "uncopied source retained");
        File doomed = fs.mkdir(root, "delete-tree", null);
        File layer = fs.mkdir(doomed, "child", null);
        write(layer, "x.txt", "x");
        WorkspaceFiles.Result deleted =
            fs.delete(Collections.singletonList(doomed), null, never, quiet);
        need(deleted.deleted.size() == 1 && !doomed.exists(), "real recursive deletion");
        need(new File(outside, "keep.txt").isFile() && newNote.isFile(),
            "unselected/outside files untouched");
        List<WorkspaceFiles.Item> list = fs.list(root, 2);
        need(list.stream().anyMatch(i -> i.file.equals(group)), "actual folders listed");
        File temporary = fs.mkdir(root, "temp-visible", null);
        Files.move(temporary.toPath(), root.toPath().resolve(WorkspaceFiles.STAGING + "orphan"));
        need(fs.list(root, 0).stream().anyMatch(i -> i.description.contains("未完成复制")),
            "abandoned staging visible for explicit cleanup");
        File unknown = new File(relocated, "unattributed");
        unknown.mkdir();
        Files.write(new File(unknown, "session.json").toPath(), "{}".getBytes("UTF-8"));
        reject(()
                   -> fs.rename(unknown, "split-unknown", null),
            "nested unknown part stays with session");
        reject(()
                   -> SessionLibrary.annotate(
                       unknown, Collections.singletonList(root), null, "bad", null, null),
            "nested unknown guard is same in library");
        File racing = write(root, "racing.txt", "source bytes");
        File racingDest = new File(dest, "racing.txt");
        AtomicBoolean collided = new AtomicBoolean();
        WorkspaceFiles.Result race =
            fs.transfer(Collections.singletonList(racing), dest, false, null, never, msg -> {
                if (collided.compareAndSet(false, true))
                    try {
                        write(dest, "racing.txt", "external destination");
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
            });
        need(race.copied.isEmpty() && !race.failures.isEmpty(), "commit collision reported");
        need(new String(Files.readAllBytes(racingDest.toPath()), "UTF-8")
                 .equals("external destination"),
            "commit never overwrites racing destination");
        File changing = write(root, "changing.txt", "original");
        AtomicBoolean mutated = new AtomicBoolean();
        reject(()
                   -> fs.transfer(Collections.singletonList(changing), dest, false, null, never,
                       msg -> {
                           if (mutated.compareAndSet(false, true))
                               try {
                                   write(root, "changing.txt", "changed while copying");
                               } catch (Exception ex) {
                                   throw new RuntimeException(ex);
                               }
                       }),
            "changing source rejected by hash/size verification");
        need(!new File(dest, "changing.txt").exists(), "unstable source never published");
        need(!WorkspaceFiles.contains(new File(root, "foo"), new File(root, "foobar")),
            "path prefix must be a complete component");
        need(WorkspaceFiles.contains(group, new File(group, "./session/unattributed")),
            "canonical dot path containment");
        WorkspaceFiles dotted = new WorkspaceFiles(new File(root, "."));
        need(dotted.path(relocated, true).equals(relocated.toPath()), "root aliases normalized");
        File partial = fs.mkdir(root, "partial-delete", null);
        write(partial, "session.json", "{}");
        for (int i = 0; i < 10; i++) write(partial, "f" + i, "x");
        AtomicBoolean erasing = new AtomicBoolean();
        AtomicInteger erasedCalls = new AtomicInteger();
        WorkspaceFiles.Result partialResult = fs.delete(Collections.singletonList(partial), null,
            () -> erasing.get() && erasedCalls.incrementAndGet() > 5, msg -> erasing.set(true));
        need(!partialResult.failures.isEmpty() && partial.isDirectory(),
            "cancelled delete reports partial result");
        need(new File(partial, "session.json").isFile(),
            "partial delete preserves top session marker");
        String longUnicode = String.join("", Collections.nCopies(58, "🌿"));
        File unicode = write(root, longUnicode, "unicode");
        fs.transfer(Collections.singletonList(unicode), dest, false, null, never, quiet);
        WorkspaceFiles.Result unicodeCopy =
            fs.transfer(Collections.singletonList(unicode), dest, false, null, never, quiet);
        need(unicodeCopy.copied.get(0).getName().getBytes("UTF-8").length <= 240,
            "unicode duplicate name stays within byte limit");
        need(!unicodeCopy.copied.get(0).getName().contains("?"),
            "duplicate suffix does not split surrogate pair");
        boolean symlink = false;
        try {
            Path link = root.toPath().resolve("link");
            Files.createSymbolicLink(link, outside.toPath());
            symlink = true;
            reject(()
                       -> fs.transfer(Collections.singletonList(link.toFile()), dest, false, null,
                           never, quiet),
                "symlink source blocked");
            reject(() -> fs.mkdir(link.toFile(), "bad", null), "symlink destination blocked");
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            System.out.println("SKIP workspace symlink creation: " + e.getClass().getSimpleName());
        }
        for (int i = 0; i < 500; i++) {
            int x = 100 + i % 100, y = 100 + i % 120, dx = i % 15 - 7, dy = i % 17 - 8;
            BubbleLayout drag =
                BubbleLayout.drag(1080, 2400, 50, 80, 10, 50, x + dx, y + dy, 148, 58, 4);
            need(
                drag.x == x + dx && drag.y == y + dy, "capsule moves exactly by finger delta " + i);
            BubbleLayout idle =
                BubbleLayout.drag(1080, 2400, 50, 80, 10, 50, x + dx, y + dy, 52, 52, 4);
            need(idle.x == drag.x && idle.y == drag.y,
                "same translation regardless of aspect ratio " + i);
        }
        System.out.println("PASS " + checks
            + (" file-workspace/drag assertions; real copy/move/rename/delete, guards, nested "
                + "sessions, cancellation; symlink=")
            + symlink);
    }
}
