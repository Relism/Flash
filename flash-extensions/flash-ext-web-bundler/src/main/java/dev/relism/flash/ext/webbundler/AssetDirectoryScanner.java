package dev.relism.flash.ext.webbundler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks a directory and computes the same etag/mimeType/immutable metadata a served asset needs.
 * Shared by {@link FilesystemAssetsSource} (runtime, dev/filesystem prod) and {@link WebBundlerBuild}
 * (build-time classpath manifest) so both agree on cache semantics for the same file.
 */
final class AssetDirectoryScanner {
    private AssetDirectoryScanner() {
    }

    record ScannedAsset(String canonicalPath, byte[] raw, byte[] br, byte[] gz, String etag, String mimeType, boolean immutable) {
    }

    static List<ScannedAsset> scan(Path root) {
        Map<String, Builder> builders = new HashMap<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String rel = "/" + root.relativize(file).toString().replace('\\', '/');
                String canonical = AssetIo.stripBrGzSuffix(rel);
                Builder b = builders.computeIfAbsent(canonical, Builder::new);
                byte[] bytes = AssetIo.read(file);
                if (rel.endsWith(".br")) b.br = bytes;
                else if (rel.endsWith(".gz")) b.gz = bytes;
                else b.raw = bytes;
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan assets from " + root, e);
        }

        List<ScannedAsset> result = new ArrayList<>();
        for (Builder b : builders.values()) {
            if (b.raw == null) continue;
            String etag = AssetIo.quotedSha1(b.raw);
            String mime = MimeTypes.byPath(b.canonicalPath);
            boolean immutable = AssetIo.isFingerprinted(b.canonicalPath);
            result.add(new ScannedAsset(b.canonicalPath, b.raw, b.br, b.gz, etag, mime, immutable));
        }
        return result;
    }

    private static final class Builder {
        private final String canonicalPath;
        private byte[] raw;
        private byte[] br;
        private byte[] gz;

        private Builder(String canonicalPath) {
            this.canonicalPath = canonicalPath;
        }
    }
}
