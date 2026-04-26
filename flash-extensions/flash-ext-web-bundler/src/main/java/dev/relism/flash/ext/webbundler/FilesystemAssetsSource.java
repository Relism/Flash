package dev.relism.flash.ext.webbundler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class FilesystemAssetsSource implements AssetsSource {
    private final Path distDir;

    private FilesystemAssetsSource(Path distDir) {
        this.distDir = distDir.normalize();
    }

    public static FilesystemAssetsSource of(Path distDir) {
        if (distDir == null) throw new IllegalArgumentException("distDir cannot be null");
        return new FilesystemAssetsSource(distDir);
    }

    @Override
    public AssetCatalog load(AssetLoadRequest request) {
        Path root = request.webRoot().resolve(distDir).normalize();
        if (!Files.exists(root)) {
            throw new IllegalStateException("distDir does not exist: " + root);
        }

        Map<String, AssetEntryBuilder> builders = new HashMap<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String rel = "/" + root.relativize(file).toString().replace('\\', '/');
                String canonical = AssetIo.stripBrGzSuffix(rel);
                String routePath = AssetPaths.joinBase(request.basePath(), canonical);
                AssetEntryBuilder b = builders.computeIfAbsent(routePath, k -> new AssetEntryBuilder(canonical));
                byte[] bytes = AssetIo.read(file);
                if (rel.endsWith(".br")) b.br = bytes;
                else if (rel.endsWith(".gz")) b.gz = bytes;
                else b.raw = bytes;
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to preload assets from " + root, e);
        }

        Map<String, AssetEntry> byRoute = new HashMap<>();
        for (Map.Entry<String, AssetEntryBuilder> e : builders.entrySet()) {
            AssetEntryBuilder b = e.getValue();
            if (b.raw == null) continue;
            String etag = AssetIo.quotedSha1(b.raw);
            String mime = MimeTypes.byPath(b.canonicalPath);
            boolean immutable = AssetIo.isFingerprinted(b.canonicalPath);
            byRoute.put(e.getKey(), new AssetEntry(b.raw, b.br, b.gz, etag, mime, immutable));
        }

        String indexRoute = AssetPaths.joinBase(request.basePath(), "/" + request.indexFile());
        AssetEntry index = byRoute.get(indexRoute);
        if (index == null) {
            throw new IllegalStateException("Missing SPA fallback file: " + indexRoute + " (from " + root + ")");
        }
        return new AssetCatalog(byRoute, index);
    }

    private static final class AssetEntryBuilder {
        private final String canonicalPath;
        private byte[] raw;
        private byte[] br;
        private byte[] gz;

        private AssetEntryBuilder(String canonicalPath) {
            this.canonicalPath = canonicalPath;
        }
    }
}
