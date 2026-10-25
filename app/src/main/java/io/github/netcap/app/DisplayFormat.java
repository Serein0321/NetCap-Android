package io.github.netcap.app;

import java.util.Locale;

/** 仅用于界面显示，不改变原始记录和导出格式。 */
final class DisplayFormat {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    static String memory(long kib) {
        long mb = Math.max(0, kib) / 1024;
        return mb >= 900 ? String.format(Locale.ROOT, "%.1f GB", mb / 1024.0) : mb + " MB";
    }
    static String hexByte(byte b) {
        int n = b & 255;
        return new String(new char[] {HEX[n >>> 4], HEX[n & 15]});
    }
    static char ascii(byte b) {
        int n = b & 255;
        return n >= 32 && n <= 126 ? (char) n : '·';
    }
}
