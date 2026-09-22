package dev.relism.flash.ext.vite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetsTest {

    @TempDir
    Path dir;

    /** What production runs from: the build inside a jar, not a directory. */
    @Test
    void theBuildIsReadFromInsideAJar() throws IOException {
        Path jar = dir.resolve("app.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : new String[]{"flash-vite/index.html", "flash-vite/assets/a-12345678.css"}) {
                out.putNextEntry(new JarEntry(name));
                out.write("x".getBytes());
            }
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            assertEquals(Set.of("/", "/index.html", "/assets/a-12345678.css"), new Assets(loader, "/").byPath.keySet());
        }
    }

    @Test
    void noBuildFailsTheBootNamingThePlugin() throws IOException {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            assertTrue(assertThrows(IllegalStateException.class, () -> new Assets(empty, "/")).getMessage().contains("flash-ext-vite-maven-plugin"));
        }
    }

    @Test
    void gzipIsAcceptedUnlessRefused() {
        assertTrue(Assets.acceptsGzip("gzip, deflate, br"));
        assertTrue(Assets.acceptsGzip("br;q=1.0, gzip;q=0.5"));
        assertFalse(Assets.acceptsGzip("gzip;q=0, br"));
        assertFalse(Assets.acceptsGzip("gzip;q=0.000"));
        assertFalse(Assets.acceptsGzip("br"));
        assertFalse(Assets.acceptsGzip(null));
    }
}
