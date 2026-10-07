package dev.relism.flash.ext.view.jte;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.ext.view.core.ViewHandler;
import dev.relism.flash.ext.view.core.ViewModel;
import dev.relism.flash.ext.view.jte.model.HomePage;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Templates compiled on the fly, as in dev, from src/test/resources/templates. */
class JteModuleTest {

    @Template("pages/home.jte")
    public static final class Home extends ViewHandler {
        @Override public ViewModel render(Request req, Response res) {
            return ViewModel.of("page", new HomePage("Flash + jte", "elorc")).with("build", "dev");
        }
    }

    @Template("pages/home.jte")
    public static final class Clash extends ViewHandler {
        @Override public ViewModel render(Request req, Response res) { return ViewModel.of("appName", "mine"); }
    }

    @Template("pages/home.jte")
    public static final class Plain extends RequestHandler {
        @Override protected Object handle(Request req, Response res) { return "plain"; }
    }

    public static final class Nameless extends ViewHandler {
        @Override public ViewModel render(Request req, Response res) { return null; }
    }

    @Template("pages/home.jte")
    public abstract static class Page extends ViewHandler {}

    public static final class Inherited extends Page {
        @Override public ViewModel render(Request req, Response res) {
            return ViewModel.of("page", new HomePage("inherited", "elorc")).with("build", "dev");
        }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new JteModule().classes(Path.of("target/jte-classes")).global("appName", req -> "FlashLab"))
            .route(HttpMethod.GET, "/", Home.class)
            .route(HttpMethod.GET, "/clash", Clash.class)
            .route(HttpMethod.GET, "/inherited", Inherited.class)).config(c -> c.dev(true));

    @Test
    void rendersTheModelAndTheGlobalsAsUtf8Html() {
        app.get("/").expectStatus(200).expectHeader("Content-Type", "text/html; charset=utf-8")
                .expectBodyContains("Flash + jte").expectBodyContains("elorc").expectBodyContains("FlashLab");
    }

    @Test
    void aTemplateIsInheritedFromTheBaseClass() {
        app.get("/inherited").expectStatus(200).expectBodyContains("inherited");
    }

    @Test
    void aModelKeyMayNotHideAGlobal() {
        app.get("/clash").expectStatus(500);
    }

    @Test
    void staticFilesAreServedWithTheirGzipForm() {
        app.get("/static/sample.css").expectStatus(200).expectHeader("Content-Type", "text/css; charset=utf-8");
        app.request().header("Accept-Encoding", "gzip").get("/static/sample.css").expectHeader("Content-Encoding", "gzip");
    }

    @Test
    void aTemplateOnSomethingThatCannotRenderItFailsTheBoot() {
        assertTrue(boot(Plain.class).getMessage().contains("no ViewHandler"));
        assertTrue(boot(Nameless.class).getMessage().contains("names no template"));
    }

    private static IllegalStateException boot(Class<? extends Handler> handler) {
        return assertThrows(IllegalStateException.class, () -> Server.start(
                App.create().install(new JteModule().classes(Path.of("target/jte-classes")).statics(null)).route(HttpMethod.GET, "/", handler),
                ServerConfig.builder().port(0).dev(true).build()).stop().join());
    }
}
