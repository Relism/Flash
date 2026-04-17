package dev.relism.ext.webbundler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class InstallCache {
    private final Path cacheFile;

    InstallCache(Path webRoot) {
        this.cacheFile = webRoot.resolve(".flash-web-bundler/install.hash");
    }

    boolean lockChanged(Path lockfile) {
        if (!Files.exists(lockfile)) return true;
        String current = digest(lockfile);
        String previous = readCached();
        return !current.equals(previous);
    }

    void writeLockHash(Path lockfile) {
        if (!Files.exists(lockfile)) return;
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, digest(lockfile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write install cache hash", e);
        }
    }

    private String readCached() {
        if (!Files.exists(cacheFile)) return "";
        try {
            return Files.readString(cacheFile, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static String digest(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file);
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Unable to hash lockfile " + file, e);
        }
    }
}
