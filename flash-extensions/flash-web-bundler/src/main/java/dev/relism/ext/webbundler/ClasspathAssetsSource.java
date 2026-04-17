package dev.relism.ext.webbundler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class ClasspathAssetsSource implements AssetsSource {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String rootPrefix;
    private final String manifestResource;

    private ClasspathAssetsSource(String rootPrefix, String manifestResource) {
        this.rootPrefix = AssetPaths.normalizePrefix(rootPrefix);
        this.manifestResource = AssetIo.stripLeadingSlash(manifestResource);
    }

    public static ClasspathAssetsSource of(String rootPrefix) {
        if (rootPrefix == null || rootPrefix.isBlank()) {
            throw new IllegalArgumentException("rootPrefix cannot be blank");
        }
        String normalized = AssetPaths.normalizePrefix(rootPrefix);
        return new ClasspathAssetsSource(normalized, normalized + "/asset-manifest.json");
    }

    public static ClasspathAssetsSource of(String rootPrefix, String manifestResource) {
        if (rootPrefix == null || rootPrefix.isBlank()) {
            throw new IllegalArgumentException("rootPrefix cannot be blank");
        }
        if (manifestResource == null || manifestResource.isBlank()) {
            throw new IllegalArgumentException("manifestResource cannot be blank");
        }
        return new ClasspathAssetsSource(rootPrefix, manifestResource);
    }

    @Override
    public AssetCatalog load(AssetLoadRequest request) {
        ClasspathAssetManifest manifest = readManifest();
        if (manifest.assets() == null || manifest.assets().isEmpty()) {
            throw new IllegalStateException("Classpath asset manifest has no assets: " + manifestResource);
        }

        Map<String, AssetEntry> byRoute = new HashMap<>();
        for (ClasspathAssetManifest.Entry entry : manifest.assets()) {
            AssetMetadata meta = toMeta(entry);
            byte[] raw = AssetIo.readClasspath(resourcePath(meta.logicalPath()));
            byte[] br = AssetIo.readClasspathOrNull(resourcePath(meta.logicalPath() + ".br"));
            byte[] gz = AssetIo.readClasspathOrNull(resourcePath(meta.logicalPath() + ".gz"));
            String routePath = AssetPaths.joinBase(request.basePath(), meta.routePath());
            byRoute.put(routePath, new AssetEntry(raw, br, gz, meta.etag(), meta.mimeType(), meta.immutable()));
        }

        String indexRoute = AssetPaths.joinBase(request.basePath(), "/" + request.indexFile());
        AssetEntry index = byRoute.get(indexRoute);
        if (index == null) {
            throw new IllegalStateException("Missing SPA fallback file in classpath manifest: " + indexRoute);
        }
        return new AssetCatalog(byRoute, index);
    }

    private ClasspathAssetManifest readManifest() {
        try (InputStream in = ClasspathAssetsSource.class.getClassLoader().getResourceAsStream(manifestResource)) {
            if (in == null) throw new IllegalStateException("Missing classpath manifest: " + manifestResource);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JSON.readValue(json, ClasspathAssetManifest.class);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid classpath manifest: " + manifestResource, e);
        }
    }

    private AssetMetadata toMeta(ClasspathAssetManifest.Entry entry) {
        if (entry == null) throw new IllegalStateException("Manifest entry cannot be null");
        String routePath = AssetIo.normalizePath(entry.routePath());
        String logicalPath = entry.resourcePath();
        if (logicalPath == null || logicalPath.isBlank()) {
            throw new IllegalStateException("Manifest entry resourcePath is required");
        }
        return new AssetMetadata(routePath, logicalPath.trim(), entry.mimeType(), entry.etag(), entry.immutable());
    }

    private String resourcePath(String logicalPath) {
        if (logicalPath.startsWith("/")) {
            return logicalPath.substring(1);
        }
        return AssetPaths.classpathJoin(rootPrefix, logicalPath);
    }
}
