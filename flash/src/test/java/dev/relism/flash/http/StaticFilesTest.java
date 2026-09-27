package dev.relism.flash.http;

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

class StaticFilesTest {

    @TempDir
    Path dir;

    /** What production runs from: files inside a jar. A .gz is a form of its file, not a file; an index answers at its directory. */
    @Test
    void filesAreReadFromInsideAJar() throws IOException {
        Path jar = dir.resolve("app.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : new String[]{"web/", "web/docs/"}) out.putNextEntry(new JarEntry(name));
            for (String name : new String[]{"web/index.html", "web/app.3f2a91bc.js", "web/app.3f2a91bc.js.gz", "web/docs/index.html"}) {
                out.putNextEntry(new JarEntry(name));
                out.write("x".getBytes());
            }
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            assertEquals(Set.of("/", "/index.html", "/app.3f2a91bc.js", "/docs/", "/docs", "/docs/index.html"),
                    new StaticFiles(loader, "web", "/", StaticFiles.HASHED).paths());
            assertEquals(Set.of("/s/", "/s", "/s/index.html", "/s/app.3f2a91bc.js", "/s/docs/", "/s/docs", "/s/docs/index.html"),
                    new StaticFiles(loader, "web", "/s/", StaticFiles.HASHED).paths());
        }
    }

    @Test
    void aMissingRootFailsTheBoot() throws IOException {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            assertThrows(IllegalStateException.class, () -> new StaticFiles(empty, "web", "/", StaticFiles.HASHED));
        }
    }

    @Test
    void hashedNamesAreRecognised() {
        assertTrue(StaticFiles.HASHED.test("js/app.3f2a91bc.js"));
        assertFalse(StaticFiles.HASHED.test("app.js"));
        assertFalse(StaticFiles.HASHED.test("app.3f2a91.js"));
    }

    @Test
    void gzipIsAcceptedUnlessRefused() {
        assertTrue(StaticFiles.acceptsGzip(view("gzip, deflate, br")));
        assertTrue(StaticFiles.acceptsGzip(view("br;q=1.0, GZIP;q=0.5")));
        assertFalse(StaticFiles.acceptsGzip(view("gzip;q=0, br")));
        assertFalse(StaticFiles.acceptsGzip(view("gzip;q=0.000")));
        assertFalse(StaticFiles.acceptsGzip(view("br")));
        assertFalse(StaticFiles.acceptsGzip(null));
    }

    private static ByteView view(String text) {
        byte[] bytes = text.getBytes();
        return new ByteView() {
            @Override public int length() { return bytes.length; }
            @Override public byte byteAt(int i) { return bytes[i]; }
        };
    }
}
