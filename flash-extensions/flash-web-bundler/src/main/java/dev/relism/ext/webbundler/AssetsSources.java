package dev.relism.ext.webbundler;

import java.nio.file.Path;

public final class AssetsSources {
    private AssetsSources() {
    }

    public static AssetsSource filesystem(Path distDir) {
        return FilesystemAssetsSource.of(distDir);
    }

    public static AssetsSource classpath(String rootPrefix) {
        return ClasspathAssetsSource.of(rootPrefix);
    }
}
