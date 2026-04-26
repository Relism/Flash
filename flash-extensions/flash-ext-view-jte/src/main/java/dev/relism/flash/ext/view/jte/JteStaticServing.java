package dev.relism.flash.ext.view.jte;

import dev.relism.flash.http.HttpStatus;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

final class JteStaticServing {
    private static final Logger log = LoggerFactory.getLogger(JteStaticServing.class);
    private static final String STATIC_ROOT = "static";
    private static final Pattern VERSIONED_FILE = Pattern.compile(".*\\.[a-fA-F0-9]{8,}\\..*");

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript"),
            Map.entry("mjs", "text/javascript"),
            Map.entry("html", "text/html"),
            Map.entry("txt", "text/plain"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("avif", "image/avif"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("map", "application/json"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip")
    );

    private final String prefix;
    private final long largeThreshold;

    private JteStaticServing(JteSettings settings) {
        this.prefix = settings.staticPrefix();
        this.largeThreshold = settings.largeFileThresholdBytes();
    }

    static JteStaticServing load(JteSettings settings) {
        if (JteStaticServing.class.getClassLoader().getResource(STATIC_ROOT) == null) {
            log.warn("flash-ext-view-jte static serving enabled but '/static' classpath root is missing; static routes disabled");
            return null;
        }
        return new JteStaticServing(settings);
    }

    boolean serve(Request req, Response res, boolean headOnly) {
        String requestPath = req.path();
        if (!requestPath.startsWith(prefix)) return false;

        String relative = requestPath.substring(prefix.length());
        if (relative.isEmpty() || "/".equals(relative)) return false;
        if (!relative.startsWith("/") || relative.contains("..") || relative.contains("\\")) return false;

        String routePath = relative;
        byte[] raw = read(routePath);
        if (raw == null) return false;

        String etag = etag(raw);
        String inm = req.header("If-None-Match");
        if (etag.equals(inm)) {
            res.status(HttpStatus.NOT_MODIFIED);
            res.header("ETag", etag);
            res.header("Vary", "Accept-Encoding");
            res.header("Cache-Control", cacheControl(isVersioned(routePath, req)));
            return true;
        }

        boolean gzipAccepted = accepts(req, "gzip");
        byte[] gz = gzipAccepted ? read(routePath + ".gz") : null;
        byte[] selected = gz != null ? gz : raw;

        res.type(mime(routePath));
        res.header("ETag", etag);
        res.header("Cache-Control", cacheControl(isVersioned(routePath, req)));
        res.header("Vary", "Accept-Encoding");
        if (gz != null) res.header("Content-Encoding", "gzip");

        if (headOnly) {
            res.body(new byte[0]);
            return true;
        }

        if (selected.length >= largeThreshold) {
            String range = req.header("Range");
            if (gz == null && range != null) {
                RangeSlice slice = parseRange(range, selected.length);
                if (slice == null) {
                    res.status(416);
                    res.header("Content-Range", "bytes */" + selected.length);
                    res.body(new byte[0]);
                    return true;
                }
                res.status(HttpStatus.PARTIAL_CONTENT);
                res.header("Accept-Ranges", "bytes");
                res.header("Content-Range", "bytes " + slice.start + "-" + slice.end + "/" + selected.length);
                res.stream(new ByteArrayInputStream(selected, slice.start, slice.length), slice.length);
                return true;
            }
            res.stream(new ByteArrayInputStream(selected), selected.length);
            return true;
        }

        res.body(selected);
        return true;
    }

    static String etagFor(byte[] raw) { return etag(raw); }
    static String mimeFor(String routePath) { return mime(routePath); }
    static boolean versionedPath(String routePath) { return VERSIONED_FILE.matcher(routePath).matches(); }
    static int rangeLength(String rangeHeader, int size) {
        RangeSlice slice = parseRange(rangeHeader, size);
        return slice == null ? -1 : slice.length;
    }

    private static String cacheControl(boolean versioned) {
        return versioned ? "public, max-age=31536000, immutable" : "no-cache";
    }

    private static boolean accepts(Request req, String encoding) {
        String value = req.header("Accept-Encoding");
        return value != null && value.contains(encoding);
    }

    private static byte[] read(String routePath) {
        String cp = STATIC_ROOT + routePath;
        try (InputStream in = resource(cp.startsWith("/") ? cp.substring(1) : cp)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read static asset: " + routePath, e);
        }
    }

    private static InputStream resource(String path) {
        return JteStaticServing.class.getClassLoader().getResourceAsStream(path);
    }

    private static String etag(byte[] raw) {
        CRC32 crc = new CRC32();
        crc.update(raw, 0, raw.length);
        return '"' + Long.toHexString(crc.getValue()) + '-' + raw.length + '"';
    }

    private static String mime(String routePath) {
        int dot = routePath.lastIndexOf('.');
        if (dot < 0 || dot == routePath.length() - 1) return "application/octet-stream";
        String ext = routePath.substring(dot + 1).toLowerCase();
        return MIME.getOrDefault(ext, "application/octet-stream");
    }

    private static boolean isVersioned(String routePath, Request req) {
        if (VERSIONED_FILE.matcher(routePath).matches()) return true;
        return req.query("v") != null || req.query("hash") != null;
    }

    private static RangeSlice parseRange(String rangeHeader, int size) {
        if (!rangeHeader.startsWith("bytes=")) return null;
        String value = rangeHeader.substring(6).trim();
        int comma = value.indexOf(',');
        if (comma >= 0) value = value.substring(0, comma).trim();
        int dash = value.indexOf('-');
        if (dash < 0) return null;

        String startPart = value.substring(0, dash).trim();
        String endPart = value.substring(dash + 1).trim();

        try {
            int start;
            int end;
            if (startPart.isEmpty()) {
                int suffixLen = Integer.parseInt(endPart);
                if (suffixLen <= 0) return null;
                if (suffixLen > size) suffixLen = size;
                start = size - suffixLen;
                end = size - 1;
            } else {
                start = Integer.parseInt(startPart);
                if (start < 0 || start >= size) return null;
                end = endPart.isEmpty() ? size - 1 : Integer.parseInt(endPart);
                if (end < start) return null;
                if (end >= size) end = size - 1;
            }
            return new RangeSlice(start, end, end - start + 1);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private record RangeSlice(int start, int end, int length) {}
}