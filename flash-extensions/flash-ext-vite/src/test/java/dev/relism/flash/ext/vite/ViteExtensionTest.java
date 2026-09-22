package dev.relism.flash.ext.vite;

import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The packaged build (src/test/resources/flash-vite) served by a real server, outside DEV. */
class ViteExtensionTest {

    private static final String JS = "/assets/app-AbCd1234.js";

    @RegisterExtension
    static final FlashTest app = FlashTest.of(flash -> flash.get("/api/before", (req, res) -> "before")
            .install(new ViteExtension()).get("/api/ping", (req, res) -> "pong"));

    @RegisterExtension
    static final FlashTest nested = FlashTest.of(flash -> flash.install(new ViteExtension().basePath("/app/")));

    @Test
    void backendRoutesWin() {
        app.get("/api/ping").expectStatus(200).expectBody("pong");
        app.get("/api/before").expectStatus(200).expectBody("before");
    }

    @Test
    void hashedAssetsAreCachedForeverTheRestRevalidates() {
        app.get(JS).expectStatus(200)
                .expectHeader("Cache-Control", "public, max-age=31536000, immutable")
                .expectHeader("Content-Type", "text/javascript; charset=utf-8")
                .expectBodyContains("built");
        app.get("/favicon.svg").expectStatus(200).expectHeader("Cache-Control", "no-cache");
    }

    @Test
    void gzipIsServedOnlyWhenAccepted() {
        app.request().header("Accept-Encoding", "br, gzip").get(JS).expectHeader("Content-Encoding", "gzip").expectHeader("Vary", "Accept-Encoding");
        assertNull(app.request().header("Accept-Encoding", "gzip;q=0").get(JS).header("Content-Encoding"));
        assertNull(app.get(JS).header("Content-Encoding"));
    }

    @Test
    void clientRoutesGetTheIndexMissingFilesA404() {
        app.get("/content/2").expectStatus(200).expectHeader("Cache-Control", "no-cache").expectBodyContains("spa");
        app.get("/assets/missing.js").expectStatus(404);
    }

    @Test
    void anUnchangedAssetIsNotSentAgain() {
        String etag = app.get(JS).header("ETag");
        app.request().header("If-None-Match", etag).get(JS).expectStatus(304)
                .expectHeader("ETag", etag).expectHeader("Cache-Control", "public, max-age=31536000, immutable");
    }

    @Test
    void headDescribesTheBodyWithoutSendingIt() {
        String length = app.get(JS).header("Content-Length");
        var head = app.request().head(JS).expectStatus(200).expectHeader("Content-Length", length);
        assertEquals("", head.body());
    }

    @Test
    void aBasePathPrefixesEveryRoute() {
        nested.get("/app" + JS).expectStatus(200);
        nested.get("/app/settings").expectStatus(200).expectBodyContains("spa");
        nested.get(JS).expectStatus(404);
    }
}
