package dev.relism.routing;

public class PathUtils {
    /**
     * Sanitizes a path segment to ensure it starts with '/' and has no trailing slash.
     * Replaces multiple slashes with a single one.
     */
    public static String sanitize(String path) {
        if (path == null || path.isBlank() || path.trim().equals("/")) {
            return "/";
        }
        
        String sanitized = path.trim().replaceAll("/{2,}", "/");
        
        if (!sanitized.startsWith("/")) {
            sanitized = "/" + sanitized;
        }
        
        if (sanitized.length() > 1 && sanitized.endsWith("/")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        
        return sanitized;
    }

    /**
     * Joins two path segments and ensures the result is sanitized.
     * Prevents "double namespace" if the path already starts with the base.
     */
    public static String join(String base, String path) {
        String sBase = sanitize(base);
        String sPath = sanitize(path);
        
        if (sBase.equals("/")) return sPath;
        if (sPath.equals("/") || sPath.isEmpty()) return sBase;
        
        // If sPath already starts with sBase, don't prepend it again
        // Example: base="/api", path="/api/users" -> "/api/users"
        if (sPath.startsWith(sBase)) {
            return sPath;
        }
        
        // Otherwise, prepend base to path
        String joined = sBase + (sPath.startsWith("/") ? sPath : "/" + sPath);
        return sanitize(joined);
    }
}
