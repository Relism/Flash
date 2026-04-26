package dev.relism.flash.ext.webbundler;

record AssetMetadata(
        String routePath,
        String logicalPath,
        String mimeType,
        String etag,
        boolean immutable
) {
    AssetMetadata {
        if (routePath == null || routePath.isBlank()) throw new IllegalArgumentException("routePath is required");
        if (logicalPath == null || logicalPath.isBlank()) throw new IllegalArgumentException("logicalPath is required");
        if (mimeType == null || mimeType.isBlank()) throw new IllegalArgumentException("mimeType is required");
        if (etag == null || etag.isBlank()) throw new IllegalArgumentException("etag is required");
    }
}
