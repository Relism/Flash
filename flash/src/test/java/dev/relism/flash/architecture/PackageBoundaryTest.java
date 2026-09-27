package dev.relism.flash.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/** The core stays core and transport: HTTP/1.1 and HTTP/2 are independent peers, and nothing in it knows an extension. */
class PackageBoundaryTest {

    @Test
    void http1DoesNotImportHttp2() throws IOException {
        assertNoImportOfPackage("dev/relism/flash/http1", "dev.relism.flash.http2");
    }

    @Test
    void http2DoesNotImportHttp1() throws IOException {
        assertNoImportOfPackage("dev/relism/flash/http2", "dev.relism.flash.http1");
    }

    @Test
    void coreDoesNotImportExtensions() throws IOException {
        assertNoImportOfPackage("dev/relism/flash", "dev.relism.flash.ext");
    }

    private static void assertNoImportOfPackage(String sourceDirRelative, String forbiddenImportPrefix) throws IOException {
        Path root = findSourceRoot(sourceDirRelative);
        if (root == null) return;

        try (Stream<Path> files = Files.walk(root)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("import " + forbiddenImportPrefix + ".")
                            || trimmed.startsWith("import " + forbiddenImportPrefix + ";")) {
                        fail(file + " imports " + forbiddenImportPrefix
                                + " and crosses a package boundary: " + trimmed);
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
