package dev.relism.flash.ext.routeviewer;

import dev.relism.flash.App;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class RouteViewerModuleTest {

    private static App app() {
        return App.create().install(new RouteViewerModule())
                .get("/hello/{name}", (req, res) -> "hi", next -> next);
    }

    @RegisterExtension
    static FlashTest dev = FlashTest.of(RouteViewerModuleTest::app).config(c -> c.dev(true));

    @RegisterExtension
    static FlashTest production = FlashTest.of(RouteViewerModuleTest::app).config(c -> c.dev(false));

    @Test
    void inDevTheUiAndTheGraphAreServed() {
        dev.get("/routeviewer").expectStatus(200).expectHeader("Content-Type", "text/html; charset=utf-8");
        dev.get("/routeviewer/app.js").expectStatus(200);
        dev.get("/routeviewer/data").expectStatus(200).expectHeader("Content-Type", "application/json")
                .expectBodyContains("\"path\":\"/hello/{name}\"").expectBodyContains("\"type\":\"middleware\"");
    }

    @Test
    void outsideDevThereIsNothing() {
        production.get("/routeviewer").expectStatus(404);
        production.get("/routeviewer/data").expectStatus(404);
    }
}
