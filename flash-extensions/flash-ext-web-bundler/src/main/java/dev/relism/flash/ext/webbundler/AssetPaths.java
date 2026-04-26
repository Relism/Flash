package dev.relism.flash.ext.webbundler;

final class AssetPaths {
    private AssetPaths() {
    }

    static String joinBase(String basePath, String routePath) {
        String base = "/".equals(basePath) ? "" : basePath;
        String route = AssetIo.normalizePath(routePath);
        return base + route;
    }

    static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return "";
        String p = prefix.replace('\\', '/');
        if (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    static String classpathJoin(String prefix, String relative) {
        String p = normalizePrefix(prefix);
        String r = AssetIo.stripLeadingSlash(relative).replace('\\', '/');
        return p.isEmpty() ? r : p + "/" + r;
    }
}
