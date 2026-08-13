package dev.relism.flash.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code R1}/{@code DEC-02}: HTTP/1.1 and HTTP/2 are peers behind the {@code ConnectionProtocol}
 * seam, never coupled to each other directly. A lightweight source-scan rather than ArchUnit —
 * this project has no bytecode-analysis test dependency yet, and one import-statement check per
 * package pair does not need one; record the choice here rather than in {@code DECISIONS.md}
 * since it is this test's own implementation detail, not a design decision affecting shipped
 * code.
 */
class PackageBoundaryTest {

    @Test
    void http1DoesNotImportH2() throws IOException {
        assertNoImportOfPackage("dev/relism/flash/http1", "dev.relism.flash.h2");
    }

    @Test
    void h2DoesNotImportHttp1() throws IOException {
        assertNoImportOfPackage("dev/relism/flash/h2", "dev.relism.flash.http1");
    }

    private static void assertNoImportOfPackage(String sourceDirRelative, String forbiddenImportPrefix) throws IOException {
        Path root = findSourceRoot(sourceDirRelative);
        // Neither package boundary can be meaningfully checked before both packages exist; once
        // dev.relism.flash.h2 gains real classes (Phase 3+) this stops being a no-op for the
        // h2-side test.
        if (root == null) return;

        try (Stream<Path> files = Files.walk(root)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("import " + forbiddenImportPrefix + ".")
                            || trimmed.startsWith("import " + forbiddenImportPrefix + ";")) {
                        fail(file + " imports " + forbiddenImportPrefix
                                + " — violates the h1/h2 package boundary (R1/DEC-02): " + trimmed);
                    }
                }
            }
        }
    }

    private static Path findSourceRoot(String packageRelativePath) {
        for (String base : List.of("flash/src/main/java", "src/main/java")) {
            Path candidate = Path.of(base).resolve(packageRelativePath);
            if (Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }
}
