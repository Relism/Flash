package dev.relism.flash.ext.security;

import java.nio.charset.StandardCharsets;

/** Where a browser may be sent back to after signing in. */
public final class Redirects {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Redirects() {}

    /**
     * {@code path} when it stays on this origin, {@code /} otherwise: an absolute URL, a
     * protocol-relative {@code //host} and a {@code /\host} a browser reads the same way are all
     * refused, so a sign-in link cannot send a user elsewhere. Control characters, spaces and
     * non-ASCII come back percent-encoded: in a {@code Location} header, a line break would start a
     * header of its own, and a tab, which browsers drop, would turn {@code /<tab>/host} into {@code //host}.
     */
    public static String localPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.startsWith("/\\")) return "/";
        StringBuilder safe = null;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c > 0x20 && c < 0x7F) {
                if (safe != null) safe.append(c);
                continue;
            }
            if (safe == null) safe = new StringBuilder(path.length() + 16).append(path, 0, i);
            int end = Character.isHighSurrogate(c) && i + 1 < path.length() ? i + 2 : i + 1;
            for (byte b : path.substring(i, end).getBytes(StandardCharsets.UTF_8)) safe.append('%').append(HEX[(b >> 4) & 15]).append(HEX[b & 15]);
            i = end - 1;
        }
        return safe == null ? path : safe.toString();
    }
}
