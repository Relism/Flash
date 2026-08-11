package dev.relism.flash.ext.webbundler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Build-time counterpart to {@link ClasspathAssetsSource}: scans a prebuilt directory (a Vite
 * {@code dist/} or a static asset folder) and writes the {@code asset-manifest.json} that
 * classpath-based production serving requires. Meant to run from a consumer's build (e.g. via
 * exec-maven-plugin's {@code exec:java}), not from the running application — see {@code docs/build-time.md}.
 */
public final class WebBundlerBuild {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private WebBundlerBuild() {
    }

    /** Scans {@code distDir} and writes {@code distDir/asset-manifest.json} for classpath serving. */
    public static void generateManifest(Path distDir) {
        if (!Files.isDirectory(distDir)) {
            throw new IllegalArgumentException("Not a directory: " + distDir);
        }
        List<ClasspathAssetManifest.Entry> entries = AssetDirectoryScanner.scan(distDir).stream()
                .map(asset -> new ClasspathAssetManifest.Entry(
                        asset.canonicalPath(),
                        AssetIo.stripLeadingSlash(asset.canonicalPath()),
                        asset.mimeType(),
                        asset.etag(),
                        asset.immutable()))
                .toList();
        if (entries.isEmpty()) {
            throw new IllegalStateException("No assets found under " + distDir);
        }
        try {
            JSON.writeValue(distDir.resolve("asset-manifest.json").toFile(), new ClasspathAssetManifest(entries));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write asset-manifest.json in " + distDir, e);
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java " + WebBundlerBuild.class.getName() + " <distDir>");
            System.exit(1);
        }
        generateManifest(Path.of(args[0]));
    }
}
