package dev.relism.flash.ext.vite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageManagerTest {

    @TempDir
    Path workspace;

    /** A project inside a workspace installs from the workspace's lockfile; its own one wins when it has one. */
    @Test
    void theNearestLockfileDecides() throws IOException {
        Path project = Files.createDirectories(workspace.resolve("apps/web"));
        Files.writeString(workspace.resolve("pnpm-lock.yaml"), "");
        PackageManager.Found found = PackageManager.of(project);
        assertEquals(PackageManager.PNPM, found.manager());
        assertTrue(found.install().getFirst().startsWith("pnpm"));
        assertEquals(List.of("install", "--frozen-lockfile"), found.install().subList(1, 3));

        Files.writeString(project.resolve("package-lock.json"), "");
        assertEquals(PackageManager.NPM, PackageManager.of(project).manager());
        assertEquals("ci", PackageManager.of(project).install().get(1));
    }

    @Test
    void npmNeedsTheSeparatorBeforeScriptArguments() {
        PackageManager.Found npm = new PackageManager.Found(PackageManager.NPM, null);
        assertEquals(List.of("run", "dev", "--", "--port", "1"), npm.run("dev", "--port", "1").subList(1, 6));
        assertEquals("install", npm.install().get(1));
        assertEquals(List.of("run", "dev", "--port", "1"), new PackageManager.Found(PackageManager.PNPM, null).run("dev", "--port", "1").subList(1, 5));
    }
}
