package dev.relism.bench;

import dev.relism.HttpServer;
import dev.relism.http.ContentType;
import dev.relism.http.HttpStatus;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

@Slf4j
public class StaticResourceManager {
    private static final int GZIP_THRESHOLD = 1024;
    private static final Map<String, ContentType> MIME_TYPES = new HashMap<>();

    static {
        MIME_TYPES.put("html", ContentType.TEXT_HTML);
        MIME_TYPES.put("css",  ContentType.TEXT_CSS);
        MIME_TYPES.put("js",   ContentType.TEXT_JAVASCRIPT);
        MIME_TYPES.put("mjs",  ContentType.TEXT_JAVASCRIPT);
        MIME_TYPES.put("png",  ContentType.IMAGE_PNG);
        MIME_TYPES.put("jpg",  ContentType.IMAGE_JPEG);
        MIME_TYPES.put("jpeg", ContentType.IMAGE_JPEG);
        MIME_TYPES.put("svg",  ContentType.IMAGE_SVG);
        MIME_TYPES.put("json", ContentType.JSON);
        MIME_TYPES.put("ico",  ContentType.BINARY);
        MIME_TYPES.put("webp", ContentType.BINARY);
    }

    public record BakedAsset(byte[] raw, byte[] gzipped, ContentType contentType, boolean immutable, String etag) {}

    private final Map<String, BakedAsset> cache = new ConcurrentHashMap<>();
    private final String mountPath;
    private BakedAsset indexFallback;

    // Campi per le statistiche del report
    private long totalRawSize = 0, totalCompressedSize = 0;
    private int compressedCount = 0;

    public StaticResourceManager(Path rootPath, String mountPath) {
        this.mountPath = normalizeMountPath(mountPath);

        long start = System.currentTimeMillis();
        bakeAll(rootPath);
        long duration = System.currentTimeMillis() - start;

        // Richiamo del report qui
        printReport(duration);
    }

    private String normalizeMountPath(String path) {
        String p = path.startsWith("/") ? path : "/" + path;
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    private void bakeAll(Path rootPath) {
        if (!Files.exists(rootPath)) return;
        try (var stream = Files.walk(rootPath)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    String relative = rootPath.relativize(file).toString().replace("\\", "/");
                    byte[] raw = Files.readAllBytes(file);
                    byte[] gzipped = compressIfBeneficial(raw, relative);
                    String etag = "\"" + Integer.toHexString(Arrays.hashCode(raw)) + "-" + raw.length + "\"";
                    boolean isImmutable = relative.matches(".*\\.[a-f0-9]{8,}\\..*");

                    BakedAsset asset = new BakedAsset(raw, gzipped, resolveContentType(relative), isImmutable, etag);
                    cache.put(relative, asset);

                    // Aggiornamento statistiche
                    totalRawSize += raw.length;
                    totalCompressedSize += (gzipped != null) ? gzipped.length : raw.length;
                    if (gzipped != null) compressedCount++;
                    if ("index.html".equals(relative)) indexFallback = asset;
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    // --- IL METODO PRINTREPORT ---
    private void printReport(long duration) {
        double saved = totalRawSize > 0 ? (1.0 - (double)totalCompressedSize / totalRawSize) * 100 : 0;
        long immutableCount = cache.values().stream().filter(BakedAsset::immutable).count();

        System.out.println("\n" + "=".repeat(45));
        System.out.printf("🚀 FLASH ASSET BAKE COMPLETE [%dms]\n", duration);
        System.out.println("-".repeat(45));
        System.out.printf("📦 Total Assets:      %d\n", cache.size());
        System.out.printf("🤐 Gzipped (On-Disk): %d\n", compressedCount);
        System.out.printf("💾 RAM Usage:         %.2f MB\n", (double)totalRawSize / (1024 * 1024));
        System.out.printf("📉 Bandwidth Saving:  %.1f%%\n", saved);
        System.out.println("-".repeat(45));
        System.out.println("🛡️  STRATEGIES ENABLED:");
        System.out.println("   • ETag Validation:   [ACTIVE] (304 Not Modified)");
        System.out.printf("   • Immutable Assets:  [%d files] (Cache: 1 year)\n", immutableCount);
        System.out.println("   • SPA Fallback:      [ENABLED] (Route -> index.html)");
        System.out.println("=".repeat(45) + "\n");
    }

    public void register(HttpServer server) {
        server.get(mountPath + "/**", (req, res) -> {
            String path = req.getRequestLine().getPath().toString();
            String subPath = path.substring(mountPath.length()).replaceFirst("^/", "");

            BakedAsset asset = subPath.isEmpty() ? indexFallback : cache.get(subPath);

            if (asset == null) {
                if (!subPath.contains(".") && indexFallback != null) {
                    return serve(res, indexFallback, req);
                }
                return res.status(HttpStatus.NOT_FOUND);
            }
            return serve(res, asset, req);
        });
    }

    private Object serve(dev.relism.models.Response res, BakedAsset asset, dev.relism.models.Request req) {
        // Caching condizionale (ETag)
        String ifNoneMatch = req.header("If-None-Match");
        if (asset.etag().equals(ifNoneMatch)) {
            return res.status(HttpStatus.NOT_MODIFIED);
        }

        res.type(asset.contentType());
        res.header("ETag", asset.etag());

        // Cache-Control Strategy
        if (asset.contentType() == ContentType.TEXT_HTML) {
            res.header("Cache-Control", "no-cache, must-revalidate");
        } else if (asset.immutable()) {
            res.header("Cache-Control", "public, max-age=31536000, immutable");
        } else {
            res.header("Cache-Control", "public, max-age=3600");
        }

        if (asset.gzipped() != null && acceptsGzip(req)) {
            res.header("Content-Encoding", "gzip");
            res.header("Vary", "Accept-Encoding");
            return res.body(asset.gzipped());
        }
        return res.body(asset.raw());
    }

    private boolean acceptsGzip(dev.relism.models.Request req) {
        String enc = req.header("Accept-Encoding");
        return enc != null && enc.contains("gzip");
    }

    private byte[] compressIfBeneficial(byte[] data, String name) {
        if (data.length < GZIP_THRESHOLD || isAlreadyCompressed(name)) return null;
        try (var baos = new ByteArrayOutputStream(); var gzip = new GZIPOutputStream(baos)) {
            gzip.write(data);
            gzip.finish();
            byte[] compressed = baos.toByteArray();
            return compressed.length < (data.length * 0.9) ? compressed : null;
        } catch (IOException e) { return null; }
    }

    private boolean isAlreadyCompressed(String n) {
        n = n.toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".ico") || n.endsWith(".webp");
    }

    private ContentType resolveContentType(String filename) {
        int dot = filename.lastIndexOf('.');
        String ext = (dot > 0) ? filename.substring(dot + 1).toLowerCase() : "";
        return MIME_TYPES.getOrDefault(ext, ContentType.BINARY);
    }
}