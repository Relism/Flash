package dev.relism.flash.ext.webbundler;

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

        Map<String, AssetEntry> byRoute = new HashMap<>();
        for (AssetDirectoryScanner.ScannedAsset asset : AssetDirectoryScanner.scan(root)) {
            String routePath = AssetPaths.joinBase(request.basePath(), asset.canonicalPath());
            byRoute.put(routePath, new AssetEntry(asset.raw(), asset.br(), asset.gz(), asset.etag(), asset.mimeType(), asset.immutable()));
        }

        String indexRoute = AssetPaths.joinBase(request.basePath(), "/" + request.indexFile());
        AssetEntry index = byRoute.get(indexRoute);
        if (index == null) {
            throw new IllegalStateException("Missing SPA fallback file: " + indexRoute + " (from " + root + ")");
        }
        return new AssetCatalog(byRoute, index);
    }
}
