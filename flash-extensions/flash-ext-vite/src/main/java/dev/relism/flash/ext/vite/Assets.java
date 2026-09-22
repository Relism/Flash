package dev.relism.flash.ext.vite;

import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.http.HttpStatus;
import dev.relism.flash.models.PreEncodedHeader;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;

import java.io.IOException;
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
import java.util.stream.Stream;

/**
 * The built frontend, read once from the classpath at boot and served from memory: every byte a
 * response carries, headers included, is prepared here, so serving allocates nothing. Compression
 * happened at build time: a {@code .gz} beside a file is its gzipped form.
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
                    if (relative.endsWith(".gz")) continue;
                    Path gzip = file.resolveSibling(file.getFileName() + ".gz");
                    assets.put(prefix + "/" + relative, asset(relative, Files.readAllBytes(file),
                            Files.isRegularFile(gzip) ? Files.readAllBytes(gzip) : null));
                }
            } finally {
                if (opened != null) opened.close();
            }
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("Cannot read the built frontend at " + index, e);
        }
        // The root is the app itself, whatever the client accepts.
        Asset root = assets.get(prefix + "/index.html");
        assets.put(prefix + "/", root);
        if (!prefix.isEmpty()) assets.put(prefix, root);
        this.byPath = Map.copyOf(assets);
        this.index = root;
    }

    /**
     * The asset at the request's path. A path that is none is a client-side route when a browser
     * navigates to it ({@code Accept} names HTML), so it gets {@code index.html}; for anything
     * else, an API call or a missing script, it is the app's own 404.
     */
    void serve(Request req, Response res) {
        Asset asset = byPath.get(req.path());
        if (asset == null) {
            String accept = req.header("Accept");
            if (accept == null || !accept.contains("text/html")) throw HttpException.notFound(req.path());
            asset = index;
        }
        res.header(asset.cache);
        if (asset.etag != null) {
            res.header(asset.etag);
            String known = req.header("If-None-Match");
            if (known != null && known.contains(asset.tag)) {
                res.status(HttpStatus.NOT_MODIFIED);
                return;
            }
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

    /**
     * Vite puts every content-hashed file under {@code assets/}: those never change, so they are
     * cached for good and never revalidated, and need no ETag. The rest revalidate by one.
     */
    private static Asset asset(String path, byte[] raw, byte[] gzip) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        byte[] type = TYPES.getOrDefault(extension, "application/octet-stream").getBytes(StandardCharsets.US_ASCII);
        if (path.startsWith("assets/")) return new Asset(raw, gzip, type, IMMUTABLE, null, null);
        String tag = "\"" + HexFormat.of().formatHex(sha1(raw)) + "\"";
        return new Asset(raw, gzip, type, REVALIDATE, new PreEncodedHeader("ETag", tag), tag);
    }

    private static byte[] sha1(byte[] raw) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(raw);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
