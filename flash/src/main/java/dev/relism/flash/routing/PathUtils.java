package dev.relism.flash.routing;

/** Route paths as declared: one leading slash, no trailing or doubled ones. Boot only. */
public final class PathUtils {

    private PathUtils() {}

    public static String sanitize(String path) {
        if (path == null || path.isBlank()) return "/";
        String sanitized = path.trim().replaceAll("/{2,}", "/");
        if (!sanitized.startsWith("/")) sanitized = "/" + sanitized;
        if (sanitized.length() > 1 && sanitized.endsWith("/")) sanitized = sanitized.substring(0, sanitized.length() - 1);
        return sanitized;
    }

    /** A {@code CONNECT} target is an authority ({@code host:port}), not a path. */
    public static String sanitizeAuthority(String authority) {
        if (authority == null) return "";
        String sanitized = authority.trim();
        while (sanitized.startsWith("/")) sanitized = sanitized.substring(1);
        return sanitized;
    }

    /** {@code path} under {@code base}. */
    public static String join(String base, String path) {
        String prefix = sanitize(base);
        String rest = sanitize(path);
        if (prefix.equals("/")) return rest;
        return rest.equals("/") ? prefix : prefix + rest;
    }
}
