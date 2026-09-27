package dev.relism.flash.ext.vite;

import dev.relism.flash.App;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The packaged build (src/test/resources/flash-vite) served by a real server, outside DEV. */
class ViteModuleTest {

    private static final String JS = "/assets/app-AbCd1234.js";
    private static final String NAVIGATION = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    @RegisterExtension
    static final FlashTest app = FlashTest.of(() -> App.create().install(new ViteModule())).config(c -> c.dev(false));

    @RegisterExtension
    static final FlashTest everything = FlashTest.of(() -> App.create().install(new ViteModule().navigationOnly(false))).config(c -> c.dev(false));

    @RegisterExtension
    static final FlashTest nested = FlashTest.of(() -> App.create().install(new ViteModule().basePath("/app/"))).config(c -> c.dev(false));

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

    /** A browser navigating gets the app, dots and all; a fetch or a script tag for nothing gets a 404. */
    @Test
    void onlyNavigationsFallBackToTheIndex() {
        for (String route : new String[]{"/content/2", "/users/ada.lovelace"}) {
            app.request().header("Accept", NAVIGATION).get(route).expectStatus(200).expectHeader("Cache-Control", "no-cache").expectBodyContains("spa");
        }
        app.request().header("Sec-Fetch-Mode", "navigate").get("/content/2").expectStatus(200).expectBodyContains("spa");
        app.request().header("Accept", "application/json").get("/api/nope").expectStatus(404);
        app.request().header("Accept", "*/*").get("/assets/missing.js").expectStatus(404);
        app.get("/content/2").expectStatus(404);
        app.get("/").expectStatus(200).expectBodyContains("spa");
        nested.get("/app").expectStatus(200).expectBodyContains("spa");
    }

    /** Opted out of the navigation check, every GET that is no file gets the page, as nginx's try_files would. */
    @Test
    void withoutTheNavigationCheckEveryMissGetsTheIndex() {
        everything.request().header("Accept", "application/json").get("/api/nope").expectStatus(200).expectBodyContains("spa");
        everything.get("/assets/missing.js").expectStatus(200).expectBodyContains("spa");
    }

    @Test
    void anUnchangedFileIsNotSentAgainAHashedOneIsNeverAsked() {
        String etag = app.get("/favicon.svg").header("ETag");
        app.request().header("If-None-Match", etag).get("/favicon.svg").expectStatus(304)
                .expectHeader("ETag", etag).expectHeader("Cache-Control", "no-cache");
        assertNull(app.get(JS).header("ETag"));
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
        nested.request().header("Accept", NAVIGATION).get("/app/settings").expectStatus(200).expectBodyContains("spa");
        nested.get(JS).expectStatus(404);
    }
}
