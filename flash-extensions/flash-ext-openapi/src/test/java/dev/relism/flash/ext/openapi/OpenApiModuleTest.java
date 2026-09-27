package dev.relism.flash.ext.openapi;

import dev.relism.flash.App;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiModuleTest {

    @ApiOperation(summary = "Health check")
    @APIResponse(responseCode = "200", description = "OK")
    public static final class Health implements Handler {
        @Override public Object handle(Request request, Response response) { return "ok"; }
    }

    @ApiOperation(summary = "Scoped users")
    public static final class Users implements Handler {
        @Override public Object handle(Request request, Response response) { return List.of(); }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new OpenApiModule("/api", "My API", "2.0.0", "desc"))
            .route(HttpMethod.GET, "/health", Health.class)
            .mount("/api/v1", v1 -> v1.route(HttpMethod.GET, "/users", Users.class)));

    @RegisterExtension
    static FlashTest scalar = FlashTest.of(() -> App.create()
            .install(new OpenApiModule("/api").ui(Ui.scalar().theme(Ui.Scalar.Theme.MOON))));

    @RegisterExtension
    static FlashTest bare = FlashTest.of(() -> App.create().install(new OpenApiModule("/api").ui(Ui.none())));

    @Test
    void servesTheDocumentAsJsonAndYamlAndThePageThatReadsIt() {
        String json = app.get("/api.json").expectStatus(200).expectHeader("Content-Type", "application/json").body();
        assertTrue(json.contains("\"openapi\":\"3.0.3\""), json);
        assertTrue(json.contains("\"title\":\"My API\""), json);
        assertTrue(json.contains("\"/health\""), json);

        app.get("/api.yaml").expectStatus(200).expectHeader("Content-Type", "application/yaml").expectBodyContains("openapi: \"3.0.3\"");

        String page = app.get("/api/docs").expectStatus(200).body();
        assertTrue(page.contains("SwaggerUIBundle") && page.contains("/api.json"), page);
    }

    @Test
    void aMountedRouteIsDocumentedUnderItsFullPath() {
        String json = app.get("/api.json").body();
        assertTrue(json.contains("\"/api/v1/users\""), json);
    }

    @Test
    void theChosenUiReplacesTheDefaultOne() {
        String page = scalar.get("/api/docs").expectStatus(200).body();
        assertTrue(page.contains("Scalar.createApiReference") && page.contains("\"theme\":\"moon\""), page);
    }

    @Test
    void noUiLeavesTheDocumentAndNothingElse() {
        bare.get("/api.json").expectStatus(200);
        bare.get("/api/docs").expectStatus(404);
    }
}
