package io.github.netcap.app;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** 查询条件是视图状态；不修改磁盘中的包，也不改变抓包核心的过滤条件。 */
final class PacketFilter {
    String text = "", groupKey = "";
    int protocol, direction = -1, readable, minBytes, maxBytes = Integer.MAX_VALUE;
    Set<Long> ids;
    byte[] magic;
    boolean prefix, legacyHex;
    PacketFilter copy() {
        PacketFilter f = new PacketFilter();
        f.text = text;
        f.groupKey = groupKey;
        f.protocol = protocol;
        f.direction = direction;
        f.readable = readable;
        f.minBytes = minBytes;
        f.maxBytes = maxBytes;
        f.legacyHex = legacyHex;
        f.magic = magic == null ? null : magic.clone();
        f.prefix = prefix;
        f.ids = ids == null ? null : new HashSet<>(ids);
        return f;
    }
    boolean metadata(PacketStore.Record r) {
        return (protocol == 0 || r.proto == protocol) && (direction < 0 || r.dir == direction)
            && r.caplen >= minBytes && r.caplen <= maxBytes && (ids == null || ids.contains(r.id));
    }
    boolean content(PacketStore.Record r, byte[] raw) {
        if (!groupKey.isEmpty() && !groupKey.equals(group(r, 0)) && !groupKey.equals(group(r, 1))
            && !groupKey.equals(group(r, 2)))
            return false;
        if (magic != null) {
            byte[] p = PacketStore.payload(r, raw);
            if (prefix) {
                if (p.length < magic.length)
                    return false;
                for (int i = 0; i < magic.length; i++)
                    if (p[i] != magic[i])
                        return false;
            } else if (!PacketStore.contains(p, magic))
                return false;
        }
        boolean isText = readable == 0 || isReadable(PacketStore.payload(r, raw));
        return readable == 0 || (readable == 1 ? isText : !isText);
    }
    static String endpoint(String address, int port) {
        return (address.indexOf(':') >= 0 ? "[" + address + "]" : address)
            + (port > 0 ? ":" + port : "");
    }
    static String group(PacketStore.Record r, int kind) {
        if (kind == 1)
            return "protocol:" + r.proto;
        if (kind == 2)
            return "size:" + sizeBucket(r.caplen);
        String a = endpoint(r.src, r.sp), b = endpoint(r.dst, r.dp);
        return "flow:" + r.proto + ":" + (a.compareTo(b) <= 0 ? a + " ↔ " + b : b + " ↔ " + a);
    }
    static int sizeBucket(int n) {
        return n < 64 ? 0 : n < 256 ? 1 : n < 1024 ? 2 : n < 4096 ? 3 : 4;
    }
    static String sizeLabel(int b) {
        return new String[] {"小于 64 B", "64–255 B", "256–1023 B", "1–4 KB", "4 KB 及以上"}[b];
    }
    static boolean isReadable(byte[] p) {
        if (p.length == 0)
            return false;
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                           .onMalformedInput(CodingErrorAction.REPORT)
                           .onUnmappableCharacter(CodingErrorAction.REPORT)
                           .decode(ByteBuffer.wrap(p))
                           .toString();
            int printable = 0, controls = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                    controls++;
                else if (!Character.isWhitespace(c))
                    printable++;
            }
            return printable >= 2 && controls <= s.length() / 20;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
