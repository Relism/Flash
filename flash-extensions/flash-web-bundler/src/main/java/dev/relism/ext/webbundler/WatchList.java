package dev.relism.ext.webbundler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class WatchList {
    private final Path webRoot;
    private final Map<Path, Long> lastKnown = new HashMap<>();

    WatchList(Path webRoot) {
        this.webRoot = webRoot;
    }

    void initialize(Iterable<String> watchEntries) {
        for (String entry : watchEntries) {
            Path file = webRoot.resolve(entry).normalize();
            lastKnown.put(file, lastModified(file));
        }
    }

    boolean changed() {
        for (Map.Entry<Path, Long> entry : lastKnown.entrySet()) {
            long current = lastModified(entry.getKey());
            if (current != entry.getValue()) {
                entry.setValue(current);
                return true;
            }
        }
        return false;
    }

    private static long lastModified(Path path) {
        try {
            if (!Files.exists(path)) return -1L;
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }
}
