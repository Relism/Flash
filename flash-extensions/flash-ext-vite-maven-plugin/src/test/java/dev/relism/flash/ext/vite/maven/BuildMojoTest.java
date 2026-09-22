package dev.relism.flash.ext.vite.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A package.json whose build script stands in for Vite's. Needs npm on the PATH. */
class BuildMojoTest {

    @TempDir
    Path dir;

    @Test
    void theBuildLandsWhereTheExtensionServesItFrom() throws Exception {
        assumeTrue(onPath(), "npm is not on the PATH");
        Path web = Files.createDirectories(dir.resolve("web"));
        Files.writeString(web.resolve("package.json"), """
                {"name":"t","private":true,"scripts":{"build":"node -e \\"const f=require('fs');f.mkdirSync('dist/assets',{recursive:true});f.writeFileSync('dist/index.html','built');f.writeFileSync('dist/assets/a-12345678.js','1');f.writeFileSync('dist/assets/b-12345678.js','x'.repeat(4096))\\""}}
                """);
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes.resolve("flash-vite"));
        Files.writeString(classes.resolve("flash-vite/stale.js"), "from the last build");

        mojo(web, classes, false).execute();

        assertEquals("built", Files.readString(classes.resolve("flash-vite/index.html")));
        assertEquals("1", Files.readString(classes.resolve("flash-vite/assets/a-12345678.js")));
        assertFalse(Files.exists(classes.resolve("flash-vite/stale.js")));
        // Big enough to gzip, and it shrinks; the 1-byte file is left alone.
        assertTrue(Files.size(classes.resolve("flash-vite/assets/b-12345678.js.gz")) < 4096);
        assertFalse(Files.exists(classes.resolve("flash-vite/assets/a-12345678.js.gz")));
    }

    @Test
    void skipBuildsNothing() throws Exception {
        mojo(dir.resolve("nowhere"), dir.resolve("classes"), true).execute();
        assertFalse(Files.exists(dir.resolve("classes")));
    }

    private static BuildMojo mojo(Path root, Path classes, boolean skip) {
        BuildMojo mojo = new BuildMojo();
        mojo.root = root.toFile();
        mojo.classes = classes.toFile();
        mojo.skip = skip;
        return mojo;
    }

    private static boolean onPath() {
        try {
            return new ProcessBuilder("npm", "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
