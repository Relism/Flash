package dev.relism.flash.ext.webbundler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

class WebBundlerBuildTest {
    private Path root;

    @AfterEach
    void tearDown() throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    /**
     * {@link ClasspathAssetsSource} resolves manifest + resources via the classloader, so the
     * scanned directory needs to actually be on the test classpath — using the directory the test
     * class itself was loaded from (Maven: {@code target/test-classes}) keeps this portable across
     * runners instead of hardcoding a build-tool-specific path.
     */
    @Test
    void generateManifest_isConsumableByClasspathAssetsSource() throws Exception {
        Path testClasses = Path.of(WebBundlerBuildTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        root = testClasses.resolve("web-bundler-build-test-" + System.nanoTime());
        Files.createDirectories(root);
        Files.writeString(root.resolve("index.html"), "<html>built</html>");
        Files.writeString(root.resolve("app.a1b2c3d4.js"), "console.log('built')");

        WebBundlerBuild.generateManifest(root);
        assertTrue(Files.exists(root.resolve("asset-manifest.json")));

        String rootPrefix = testClasses.relativize(root).toString().replace('\\', '/');
        ClasspathAssetsSource source = ClasspathAssetsSource.of(rootPrefix);
        AssetCatalog catalog = source.load(new AssetLoadRequest("/", "index.html", RuntimeEnvironment.PROD, Path.of(".")));

        assertNotNull(catalog.index());
        assertTrue(new String(catalog.index().raw()).contains("built"));

        AssetEntry js = catalog.find("/app.a1b2c3d4.js");
        assertNotNull(js);
        assertTrue(js.immutable());
        assertEquals("text/javascript", js.mimeType());
        assertFalse(catalog.index().immutable());
    }

    @Test
    void generateManifest_emptyDirectory_throws() throws Exception {
        Path testClasses = Path.of(WebBundlerBuildTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        root = testClasses.resolve("web-bundler-build-empty-" + System.nanoTime());
        Files.createDirectories(root);

        assertThrows(IllegalStateException.class, () -> WebBundlerBuild.generateManifest(root));
    }
}
