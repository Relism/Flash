package dev.relism.flash.ext.webbundler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AssetsSourceTest {
    @TempDir
    Path tempDir;

    @Test
    void filesystemSource_loadsAssetsAndIndex() throws Exception {
        Path webRoot = tempDir.resolve("web");
        Path dist = webRoot.resolve("dist");
        Files.createDirectories(dist);
        Files.writeString(dist.resolve("index.html"), "<html>ok</html>");
        Files.writeString(dist.resolve("app.abcd1234.js"), "console.log(1)");

        AssetsSource source = FilesystemAssetsSource.of(Path.of("dist"));
        AssetCatalog catalog = source.load(new AssetLoadRequest("/app", "index.html", RuntimeEnvironment.PROD, webRoot));

        assertNotNull(catalog.index());
        AssetEntry js = catalog.find("/app/app.abcd1234.js");
        assertNotNull(js);
        assertTrue(js.immutable());
    }

    @Test
    void classpathSource_missingManifestFailsFast() {
        AssetsSource source = ClasspathAssetsSource.of("missing-root");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> source.load(new AssetLoadRequest("/", "index.html", RuntimeEnvironment.PROD, Path.of("."))));
        assertTrue(ex.getMessage().contains("manifest"));
    }
}
