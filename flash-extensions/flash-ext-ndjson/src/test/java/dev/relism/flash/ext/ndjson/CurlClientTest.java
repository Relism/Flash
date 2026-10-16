package dev.relism.flash.ext.ndjson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.ext.jackson.json.Json;
import dev.relism.flash.ext.jackson.json.JsonModule;
import dev.relism.flash.http.HttpMethod;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** curl, unbuffered, as a streaming client over HTTP/1.1 and HTTP/2. Needs curl with HTTP/2. */
@Tag("interop")
class CurlClientTest {

    private static Server server;

    @BeforeAll
    static void start() {
        server = Server.start(App.create().install(new JsonModule(), new NdjsonModule(Json.class))
                        .route(HttpMethod.POST, "/double", NdjsonTest.Doubler.class)
                        .route(HttpMethod.GET, "/feed", NdjsonTest.Feed.class),
                ServerConfig.builder().host("127.0.0.1").port(0).http2CleartextEnabled(true).build());
    }

    @AfterAll
    static void stop() {
        server.stop().join();
    }

    private static Process curl(String version, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("curl", "--silent", "--show-error", "--no-buffer", version));
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    /** The first line reaches the client while the handler still waits to write the second. */
    @ParameterizedTest
    @ValueSource(strings = {"--http1.1", "--http2-prior-knowledge"})
    void aFlushedLineArrivesBeforeTheHandlerReturns(String version) throws Exception {
        NdjsonTest.Feed.next = new CountDownLatch(1);
        Process feed = curl(version, url("/feed"));
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(feed.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("{\"name\":\"first\",\"quantity\":1}", lines.readLine());
            NdjsonTest.Feed.next.countDown();
            assertEquals("{\"name\":\"second\",\"quantity\":2}", lines.readLine());
            assertEquals(null, lines.readLine());
        }
        assertTrue(feed.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, feed.exitValue());
    }

    /** A clean stream ends with curl's success; one cut off after its first line is an error, not a short success. */
    @ParameterizedTest
    @ValueSource(strings = {"--http1.1", "--http2-prior-knowledge"})
    void aCutOffStreamIsAnErrorForTheClient(String version) throws Exception {
        Process clean = curl(version, "-H", "content-type: application/x-ndjson",
                "--data-binary", "{\"name\":\"a\",\"quantity\":1}\n", url("/double"));
        assertEquals("{\"name\":\"a\",\"quantity\":2}\n", new String(clean.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(clean.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, clean.exitValue());

        Process cut = curl(version, "-H", "content-type: application/x-ndjson", "-H", "x-flush: yes",
                "--data-binary", "{\"name\":\"a\",\"quantity\":1}\nnot json\n", url("/double"));
        assertEquals("{\"name\":\"a\",\"quantity\":2}\n", new String(cut.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(cut.waitFor(10, TimeUnit.SECONDS));
        assertNotEquals(0, cut.exitValue());
    }
}
