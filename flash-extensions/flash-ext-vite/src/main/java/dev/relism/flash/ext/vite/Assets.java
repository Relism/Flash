package dev.relism.flash.ext.vite;

import dev.relism.flash.http.HttpStatus;
import dev.relism.flash.models.PreEncodedHeader;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * The built frontend, read once from the classpath at boot and served from memory: every byte a
 * response carries, headers included, is prepared here, so serving allocates nothing.
 */
final class Assets {

    private static final PreEncodedHeader IMMUTABLE = new PreEncodedHeader("Cache-Control", "public, max-age=31536000, immutable");
    private static final PreEncodedHeader REVALIDATE = new PreEncodedHeader("Cache-Control", "no-cache");
    private static final PreEncodedHeader VARY = new PreEncodedHeader("Vary", "Accept-Encoding");
    private static final PreEncodedHeader GZIP = new PreEncodedHeader("Content-Encoding", "gzip");

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("map", "application/json"),
            Map.entry("webmanifest", "application/manifest+json"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("xml", "application/xml"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("wasm", "application/wasm"));

    /** Formats that are not compressed already; the rest (images, woff) gain nothing from gzip. */
    private static final Set<String> COMPRESSIBLE = Set.of("html", "js", "mjs", "css", "json", "map", "webmanifest", "txt", "xml", "svg", "ttf", "otf", "wasm");

    record Asset(byte[] raw, byte[] gzip, byte[] type, PreEncodedHeader cache, PreEncodedHeader etag, String tag) {}

    final Map<String, Asset> byPath;
    private final Asset index;

    /** Every file under {@link ViteExtension#CLASSPATH} on {@code loader}'s classpath, routed under {@code basePath}. */
    Assets(ClassLoader loader, String basePath) {
        URL index = loader.getResource(ViteExtension.CLASSPATH + "/index.html");
        if (index == null) {
            throw new IllegalStateException("No built frontend on the classpath (" + ViteExtension.CLASSPATH + "/index.html): "
                    + "package the application with flash-ext-vite-maven-plugin, or run it with FLASH_ENV=dev.");
        }
        String prefix = "/".equals(basePath) ? "" : basePath;
        Map<String, Asset> assets = new HashMap<>();
        try {
            URI uri = index.toURI();
            FileSystem opened = null;
            if ("jar".equals(uri.getScheme())) {
                try {
                    opened = FileSystems.newFileSystem(uri, Map.of());
                } catch (FileSystemAlreadyExistsException alreadyOpen) {
                    // Path.of below resolves against the one already open.
                }
            }
            Path root = Path.of(uri).getParent();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    assets.put(prefix + "/" + relative, asset(relative, Files.readAllBytes(file)));
                }
            } finally {
                if (opened != null) opened.close();
            }
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("Cannot read the built frontend at " + index, e);
        }
        this.byPath = Map.copyOf(assets);
        this.index = byPath.get(prefix + "/index.html");
    }

    /**
     * The asset at the request's path; a path with no file extension is a client-side route and
     * gets {@code index.html}, one with an extension that matches nothing is a 404.
     */
    void serve(Request req, Response res) {
        String path = req.path();
        Asset asset = byPath.get(path);
        if (asset == null) {
            if (path.lastIndexOf('.') > path.lastIndexOf('/')) {
                res.status(HttpStatus.NOT_FOUND);
                return;
            }
            asset = index;
        }
        res.header(asset.cache).header(asset.etag);
        String known = req.header("If-None-Match");
        if (known != null && known.contains(asset.tag)) {
            res.status(HttpStatus.NOT_MODIFIED);
            return;
        }
        res.type(asset.type);
        if (asset.gzip != null) {
            res.header(VARY);
            if (acceptsGzip(req.header("Accept-Encoding"))) {
                res.header(GZIP).body(asset.gzip);
                return;
            }
        }
        res.body(asset.raw);
    }

    /** Listed in {@code Accept-Encoding}, and not with a q of zero. */
    static boolean acceptsGzip(String accept) {
        if (accept == null) return false;
        int at = accept.indexOf("gzip");
        if (at < 0) return false;
        int end = accept.indexOf(',', at);
        if (end < 0) end = accept.length();
        int q = accept.indexOf("q=", at);
        if (q < 0 || q > end) return true;
        for (int i = q + 2; i < end; i++) {
            char c = accept.charAt(i);
            if (c >= '1' && c <= '9') return true;
            if (c != '0' && c != '.') break;
        }
        return false;
    }

    /** Vite puts every content-hashed file under {@code assets/}: those never change, the rest revalidate. */
    private static Asset asset(String path, byte[] raw) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        byte[] type = TYPES.getOrDefault(extension, "application/octet-stream").getBytes(StandardCharsets.US_ASCII);
        byte[] gzip = COMPRESSIBLE.contains(extension) && raw.length >= 1024 ? gzip(raw) : null;
        String tag = "\"" + HexFormat.of().formatHex(sha1(raw)) + "\"";
        return new Asset(raw, gzip != null && gzip.length < raw.length ? gzip : null, type,
                path.startsWith("assets/") ? IMMUTABLE : REVALIDATE, new PreEncodedHeader("ETag", tag), tag);
    }

    private static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2);
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] sha1(byte[] raw) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(raw);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
