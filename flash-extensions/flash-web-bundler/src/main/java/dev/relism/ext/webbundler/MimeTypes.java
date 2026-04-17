package dev.relism.ext.webbundler;

import java.util.Map;

final class MimeTypes {
    private static final Map<String, String> EXT = Map.ofEntries(
            Map.entry("html", "text/html"),
            Map.entry("js", "text/javascript"),
            Map.entry("css", "text/css"),
            Map.entry("json", "application/json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("txt", "text/plain")
    );

    private MimeTypes() {}

    static String byPath(String path) {
        int idx = path.lastIndexOf('.');
        if (idx < 0 || idx == path.length() - 1) return "application/octet-stream";
        String ext = path.substring(idx + 1).toLowerCase();
        return EXT.getOrDefault(ext, "application/octet-stream");
    }
}
