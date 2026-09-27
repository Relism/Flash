package dev.relism.flash.ext.view.thymeleaf;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.ext.view.core.ViewHandler;
import dev.relism.flash.ext.view.core.ViewModel;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** src/test/resources/templates, rendered by a real server. */
class ThymeleafModuleTest {

    @Template("pages/home")
    public static final class Home extends ViewHandler {
        @Override public ViewModel render(Request req, Response res) {
            return ViewModel.of("title", "Home").with("id", 42).with("page", 2);
        }
    }

    @Fragment(template = "pages/home", value = "rows")
    public static final class Rows extends ViewHandler {
        @Override public ViewModel render(Request req, Response res) { return ViewModel.of("id", 42); }
    }

    @Fragment(template = "pages/home")
    public static final class Content extends ViewHandler {
        @Override public ViewModel render(Request req, Response res) { return ViewModel.of("title", "t").with("id", 1); }
    }

    @Template("pages/with-globals")
    public static final class Globals extends ViewHandler {
        @Override public ViewModel render(Request req, Response res) { return ViewModel.empty(); }
    }

    @Template("pages/home")
    public static final class Reserved extends ViewHandler {
        @Override public ViewModel render(Request req, Response res) { return ViewModel.of("global", "mine"); }
    }

    @Template("pages/link-cases")
    public static final class Links extends ViewHandler {
        @Override public ViewModel render(Request req, Response res) { return ViewModel.empty(); }
    }

    @Template("pages/home") @Fragment(template = "pages/home")
    public static final class Both extends ViewHandler {
        @Override public ViewModel render(Request req, Response res) { return null; }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new ThymeleafModule().global("appName", req -> "Flash").global("path", Request::path))
            .route(HttpMethod.GET, "/home", Home.class)
            .route(HttpMethod.GET, "/rows", Rows.class)
            .route(HttpMethod.GET, "/content", Content.class)
            .route(HttpMethod.GET, "/globals", Globals.class)
            .route(HttpMethod.GET, "/reserved", Reserved.class)
            .route(HttpMethod.GET, "/links", Links.class));

    @Test
    void aTemplateRendersWithItsLinksBuilt() {
        app.get("/home").expectStatus(200).expectHeader("Content-Type", "text/html; charset=utf-8")
                .expectBodyContains("<h1>Home</h1>").expectBodyContains("/users/42?page=2");
    }

    @Test
    void aFragmentRendersAloneTheDefaultOneWhenUnnamed() {
        String rows = app.get("/rows").expectStatus(200).expectBodyContains("row-42").body();
        assertTrue(!rows.contains("<h1>"), rows);
        app.get("/content").expectStatus(200).expectBodyContains("content-t");
    }

    @Test
    void globalsAreUnderTheirOwnName() {
        app.get("/globals").expectStatus(200).expectBodyContains("Flash").expectBodyContains("/globals");
        app.get("/reserved").expectStatus(500);
    }

    @Test
    void linksWithoutOrWithEmptyParametersStillRender() {
        app.get("/links").expectStatus(200).expectBodyContains("href=\"/static\"")
                .expectBodyContains("href=\"/users/\"").expectBodyContains("href=\"/search?q=\"");
    }

    @Test
    void aTemplateAndAFragmentTogetherFailTheBoot() {
        assertThrows(IllegalStateException.class, () -> Server.start(
                App.create().install(new ThymeleafModule()).route(HttpMethod.GET, "/", Both.class),
                ServerConfig.builder().port(0).build()).stop().join());
    }
}
