package dev.relism.flash.ext.webbundler;

import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Two frontend layouts served by a real server. Each is laid out on disk inside the
 * application's own configure(), which the harness runs lazily at first access — by then
 * {@link TempDir} is populated, and a server whose test never runs is never booted.
 */
class WebBundlerExtensionIntegrationTest {

    @TempDir
    static Path tempDir;

    @RegisterExtension
    static FlashTest managed = FlashTest.of(app -> {
        Path webRoot = write(tempDir.resolve("web").resolve("dist"),
                "index.html", "<html>spa</html>",
                "app.js", "console.log('ok');").getParent();

        app.install(new WebBundlerExtension(WebBundlerConfig.builder()
                .runtimeMode(RuntimeMode.PROD)
                .operationMode(OperationMode.MANAGED)
                .webRoot(webRoot)
                .assetsFromFilesystem(Path.of("dist"))
                .basePath("/app")
                .build()));
        app.get("/api/ping", (req, res) -> "pong");
    });

    // PROD is deterministic in a test JVM (Flash.DEV depends on env/system-property detection
    // that can't be forced per-test); STATIC's actual guarantee — that it never orchestrates,
    // in DEV or PROD — is enforced structurally by the same requiresOrchestration() gate in
    // both WebBundlerExtension.provide() and .routes(), not by this test.
    @RegisterExtension
    static FlashTest staticFrontend = FlashTest.of(app -> {
        Path webRoot = write(tempDir.resolve("public"),
                "index.html", "<html>static</html>",
                "style.css", "body{color:red}");

        app.install(new WebBundlerExtension(WebBundlerConfig.builder()
                .runtimeMode(RuntimeMode.PROD)
                .frontendType(FrontendType.STATIC)
                .webRoot(webRoot)
                .build()));
        app.get("/api/ping", (req, res) -> "pong");
    });

    @Test
    void prodMode_servesAssetsAndFallback_withoutBreakingBackendRoutes() {
        managed.get("/api/ping").expectStatus(200).expectBody("pong");
        managed.get("/app/app.js").expectStatus(200).expectBodyContains("console.log");
        managed.get("/app/some/client/route").expectStatus(200).expectBodyContains("spa");
    }

    @Test
    void staticFrontend_servesAssetsWithoutOrchestration() {
        staticFrontend.get("/api/ping").expectStatus(200).expectBody("pong");
        staticFrontend.get("/style.css").expectStatus(200).expectBodyContains("color:red");
    }

    /** Creates {@code directory} and writes the given name/content pairs into it. */
    private static Path write(Path directory, String... nameThenContent) {
        try {
            Files.createDirectories(directory);
            for (int i = 0; i < nameThenContent.length; i += 2)
                Files.writeString(directory.resolve(nameThenContent[i]), nameThenContent[i + 1]);
            return directory;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
