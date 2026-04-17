package dev.relism.ext.webbundler;

import java.nio.file.Path;

public record AssetLoadRequest(String basePath, String indexFile, RuntimeEnvironment environment, Path webRoot) {
    public AssetLoadRequest {
        if (basePath == null || basePath.isBlank()) throw new IllegalArgumentException("basePath is required");
        if (indexFile == null || indexFile.isBlank()) throw new IllegalArgumentException("indexFile is required");
        if (environment == null) throw new IllegalArgumentException("environment is required");
        if (webRoot == null) throw new IllegalArgumentException("webRoot is required");
    }
}
