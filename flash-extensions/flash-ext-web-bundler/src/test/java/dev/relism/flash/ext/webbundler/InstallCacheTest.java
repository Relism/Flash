package dev.relism.flash.ext.webbundler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallCacheTest {
    @TempDir
    Path tempDir;

    @Test
    void lockHashChanges_areDetected() throws Exception {
        Path lockfile = tempDir.resolve("package-lock.json");
        Files.writeString(lockfile, "{\"a\":1}");

        InstallCache cache = new InstallCache(tempDir);
        assertTrue(cache.lockChanged(lockfile));
        cache.writeLockHash(lockfile);
        assertFalse(cache.lockChanged(lockfile));

        Files.writeString(lockfile, "{\"a\":2}");
        assertTrue(cache.lockChanged(lockfile));
    }
}
