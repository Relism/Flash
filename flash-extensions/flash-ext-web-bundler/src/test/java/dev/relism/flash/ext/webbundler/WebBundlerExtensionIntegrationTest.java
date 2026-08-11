package dev.relism.flash.ext.webbundler;

import dev.relism.flash.extension.FlashApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebBundlerExtensionIntegrationTest {
    @TempDir
    Path tempDir;

    private FlashApp app;

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    @Test
    void prodMode_servesAssetsAndFallback_withoutBreakingBackendRoutes() throws Exception {
        Path webRoot = tempDir.resolve("web");
        Path dist = webRoot.resolve("dist");
        Files.createDirectories(dist);
        Files.writeString(dist.resolve("index.html"), "<html>spa</html>");
        Files.writeString(dist.resolve("app.js"), "console.log('ok');");

        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }

        WebBundlerConfig config = WebBundlerConfig.builder()
                .runtimeMode(RuntimeMode.PROD)
                .operationMode(OperationMode.MANAGED)
                .webRoot(webRoot)
                .assetsFromFilesystem(Path.of("dist"))
                .basePath("/app")
                .build();

        app = FlashApp.create(port);
        app.install(new WebBundlerExtension(config));
        app.get("/api/ping", (req, res) -> "pong");
        app.start();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> backend = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ping")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, backend.statusCode());
        assertEquals("pong", backend.body());

        HttpResponse<String> asset = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/app/app.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, asset.statusCode());
        assertTrue(asset.body().contains("console.log"));

        HttpResponse<String> fallback = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/app/some/client/route")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, fallback.statusCode());
        assertTrue(fallback.body().contains("spa"));
    }

    @Test
    void staticFrontend_servesAssetsWithoutOrchestration() throws Exception {
        Path webRoot = tempDir.resolve("public");
        Files.createDirectories(webRoot);
        Files.writeString(webRoot.resolve("index.html"), "<html>static</html>");
        Files.writeString(webRoot.resolve("style.css"), "body{color:red}");

        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }

        // PROD is deterministic in a test JVM (Flash.DEV depends on env/system-property detection
        // that can't be forced per-test); STATIC's actual guarantee — that it never orchestrates,
        // in DEV or PROD — is enforced structurally by the same requiresOrchestration() gate in
        // both WebBundlerExtension.provide() and .routes(), not by this test.
        WebBundlerConfig config = WebBundlerConfig.builder()
                .runtimeMode(RuntimeMode.PROD)
                .frontendType(FrontendType.STATIC)
                .webRoot(webRoot)
                .build();

        app = FlashApp.create(port);
        app.install(new WebBundlerExtension(config));
        app.get("/api/ping", (req, res) -> "pong");
        app.start();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> backend = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ping")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, backend.statusCode());
        assertEquals("pong", backend.body());

        HttpResponse<String> asset = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/style.css")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, asset.statusCode());
        assertTrue(asset.body().contains("color:red"));
    }

}
