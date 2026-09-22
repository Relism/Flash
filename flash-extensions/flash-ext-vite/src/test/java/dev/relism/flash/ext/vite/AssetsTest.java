package dev.relism.flash.ext.vite;

import dev.relism.fpr.core.ByteView;
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
            assertEquals(Set.of("/", "/index.html", "/assets/a-12345678.css"), new Assets(loader, "/", true).byPath.keySet());
        }
    }

    @Test
    void noBuildFailsTheBootNamingThePlugin() throws IOException {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            assertTrue(assertThrows(IllegalStateException.class, () -> new Assets(empty, "/", true)).getMessage().contains("flash-ext-vite-maven-plugin"));
        }
    }

    @Test
    void gzipIsAcceptedUnlessRefused() {
        assertTrue(Assets.acceptsGzip(view("gzip, deflate, br")));
        assertTrue(Assets.acceptsGzip(view("br;q=1.0, GZIP;q=0.5")));
        assertFalse(Assets.acceptsGzip(view("gzip;q=0, br")));
        assertFalse(Assets.acceptsGzip(view("gzip;q=0.000")));
        assertFalse(Assets.acceptsGzip(view("br")));
        assertFalse(Assets.acceptsGzip(null));
    }

    @Test
    void searchingIgnoresAsciiCaseAndFindsTheFirstMatch() {
        byte[] html = "text/html".getBytes();
        assertEquals(0, Assets.indexOf(view("Text/HTML,*/*"), html, 0));
        assertEquals(12, Assets.indexOf(view("application/text/html"), html, 0));
        assertEquals(-1, Assets.indexOf(view("text/htm"), html, 0));
        assertEquals(-1, Assets.indexOf(null, html, 0));
    }

    private static ByteView view(String text) {
        byte[] bytes = text.getBytes();
        return new ByteView() {
            @Override public int length() { return bytes.length; }
            @Override public byte byteAt(int i) { return bytes[i]; }
        };
    }
}
