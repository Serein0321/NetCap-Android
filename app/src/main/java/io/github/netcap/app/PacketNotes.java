package io.github.netcap.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** 会话旁路收藏：只保存序号与别名，原始 PCAP 和索引始终不变。 */
final class PacketNotes {
    static Map<Long, String> read(File session) throws IOException {
        Map<Long, String> notes = new LinkedHashMap<>();
        File file = new File(session, "bookmarks.json");
        if (!file.exists())
            return notes;
        try {
            JSONObject j = new JSONObject(PacketStore.readText(file));
            Iterator<String> keys = j.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long id = Long.parseLong(key);
                if (id > 0)
                    notes.put(id, j.getString(key));
            }
            return notes;
        } catch (Exception e) {
            throw new IOException("保存列表损坏；原始包未受影响", e);
        }
    }
    static synchronized Map<Long, String> edit(
        File session, Collection<Long> ids, boolean remove, String name) throws IOException {
        Map<Long, String> notes = read(session);
        for (Long id : ids) {
            if (id == null || id <= 0 || id > PacketStore.count(session))
                throw new IOException("收藏包序号不属于当前会话");
            if (remove)
                notes.remove(id);
            else
                notes.put(id, name == null ? notes.getOrDefault(id, "") : name.trim());
        }
        JSONObject j = new JSONObject();
        try {
            for (Map.Entry<Long, String> e : notes.entrySet())
                j.put(e.getKey().toString(), e.getValue());
        } catch (JSONException e) {
            throw new IOException(e);
        }
        byte[] bytes = j.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4 * 1024 * 1024)
            throw new IOException("保存列表注释超过 4 MB，请分会话整理");
        File tmp = new File(session, "bookmarks.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        PacketStore.replace(tmp, new File(session, "bookmarks.json"));
        return notes;
    }
}
