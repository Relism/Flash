package dev.relism.flash.ext.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.extension.RouteEvent;
import dev.relism.flash.extension.RouteListener;
import dev.relism.flash.models.Request;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.GET;
import dev.relism.flash.routing.MiddlewareNode;
import dev.relism.flash.websocket.WebSocketEndpoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiExtensionTest {

    @GET("/health")
    @ApiOperation(summary = "Health check")
    @APIResponse(responseCode = "200", description = "OK")
    static class HealthHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return "ok";
        }
    }

    /** Routes are collected after the ready callbacks, so the document is rendered by the first request. */
    private static byte[] body(TestRegistrar app, String path) throws Exception {
        Response res = new Response(200, ContentType.NONE);
        assertNull(app.route(HttpMethod.GET, path).handle(null, res));
        return res.getBody();
    }

    @Test
    void serves_the_document_as_json_and_yaml_and_the_page_that_reads_it() throws Exception {
        FlashContext ctx = new FlashContext();
        OpenApiExtension ext = new OpenApiExtension("/api", "My API", "2.0.0", "desc");
        TestRegistrar app = new TestRegistrar(ctx);
        ext.configure(app, ctx);
        ctx.complete();
        emitRoute(ctx, HttpMethod.GET, "/health", "/", HealthHandler.class);

        Response res = new Response(200, ContentType.NONE);
        app.route(HttpMethod.GET, "/api.json").handle(null, res);
        assertEquals(new String(ContentType.JSON.getBytes()), new String(res.getContentType()));
        String json = new String(res.getBody());
        assertTrue(json.contains("\"openapi\":\"3.0.3\""));
        assertTrue(json.contains("\"title\":\"My API\""));
        assertTrue(json.contains("/health"));

        Response yamlRes = new Response(200, ContentType.NONE);
        app.route(HttpMethod.GET, "/api.yaml").handle(null, yamlRes);
        assertEquals("application/yaml", new String(yamlRes.getContentType()));
        assertTrue(new String(yamlRes.getBody()).contains("openapi: \"3.0.3\""));

        Response page = new Response(200, ContentType.NONE);
        app.route(HttpMethod.GET, "/api/docs").handle(null, page);
        assertEquals(new String(ContentType.TEXT_HTML.getBytes()), new String(page.getContentType()));
        assertTrue(new String(page.getBody()).contains("SwaggerUIBundle"));
        assertTrue(new String(page.getBody()).contains("/api.json"));
    }

    @Test
    void the_chosen_ui_replaces_the_default_one() throws Exception {
        FlashContext ctx = new FlashContext();
        TestRegistrar app = new TestRegistrar(ctx);
        new OpenApiExtension("/api").ui(Ui.scalar().theme(Ui.Scalar.Theme.MOON)).configure(app, ctx);
        ctx.complete();

        String page = new String(body(app, "/api/docs"));
        assertTrue(page.contains("Scalar.createApiReference"));
        assertTrue(page.contains("\"theme\":\"moon\""));
    }

    @Test
    void no_ui_leaves_the_document_and_nothing_else() {
        FlashContext ctx = new FlashContext();
        TestRegistrar app = new TestRegistrar(ctx);
        new OpenApiExtension("/api").ui(Ui.none()).configure(app, ctx);
        ctx.complete();

        assertNotNull(app.route(HttpMethod.GET, "/api.json"));
        assertNotNull(app.route(HttpMethod.GET, "/api.yaml"));
        assertNull(app.route(HttpMethod.GET, "/api/docs"));
    }

    /** Encoded once and handed out as it is, until a route the document does not have yet arrives. */
    @Test
    void the_encoded_document_is_reused_until_an_operation_is_added() throws Exception {
        FlashContext ctx = new FlashContext();
        OpenApiExtension ext = new OpenApiExtension("/api");
        TestRegistrar app = new TestRegistrar(ctx);
        ext.configure(app, ctx);
        ctx.complete();
        emitRoute(ctx, HttpMethod.GET, "/health", "/", HealthHandler.class);

        byte[] first = body(app, "/api.json");
        assertSame(first, body(app, "/api.json"));

        emitRoute(ctx, HttpMethod.GET, "/users", "/", ScopedUsersHandler.class);
        byte[] second = body(app, "/api.json");
        assertNotSame(first, second);
        assertTrue(new String(second).contains("/users"));
    }

    @Test
    void routes_use_mapper_from_context_when_provided() throws Exception {
        FlashContext ctx = new FlashContext();
        OpenApiExtension ext = new OpenApiExtension();
        ObjectMapper mapper = new ObjectMapper();
        ctx.provide(ObjectMapper.class, mapper);

        TestRegistrar app = new TestRegistrar(ctx);
        ext.configure(app, ctx);
        ctx.complete();

        Response jsonRes = new Response(200, ContentType.NONE);
        app.route(HttpMethod.GET, "/openapi.json").handle(null, jsonRes);
        assertTrue(new String(jsonRes.getBody()).contains("\"openapi\":\"3.0.3\""));
    }

    @GET("/users")
    @ApiOperation(summary = "Scoped users")
    static class ScopedUsersHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return List.of();
        }
    }

    @Test
    void collects_full_runtime_path_from_route_event() {
        FlashContext ctx = new FlashContext();
        OpenApiExtension ext = new OpenApiExtension();
        ext.configure(new TestRegistrar(ctx), ctx);

        emitRoute(ctx, HttpMethod.GET, "/api/v1/users", "/api/v1", ScopedUsersHandler.class);

        ctx.complete();
        OpenApiBuilder builder = ctx.require(OpenApiBuilder.class);
        Map<String, Object> spec = builder.build();
        Map<String, Object> paths = cast(spec.get("paths"));
        assertTrue(paths.containsKey("/api/v1/users"));
        assertFalse(paths.containsKey("/users"));
    }

    @Test
    void normalizes_double_slash_paths_from_events() {
        FlashContext ctx = new FlashContext();
        OpenApiExtension ext = new OpenApiExtension();
        ext.configure(new TestRegistrar(ctx), ctx);

        emitRoute(ctx, HttpMethod.GET, "//blogs", "/", ScopedUsersHandler.class);

        ctx.complete();
        OpenApiBuilder builder = ctx.require(OpenApiBuilder.class);
        Map<String, Object> spec = builder.build();
        Map<String, Object> paths = cast(spec.get("paths"));
        assertTrue(paths.containsKey("/blogs"));
        assertFalse(paths.containsKey("//blogs"));
    }

    @SuppressWarnings("unchecked")
    private static List<RouteListener> listeners(FlashContext ctx) {
        try {
            java.lang.reflect.Method m = FlashContext.class.getDeclaredMethod("routeListeners");
            m.setAccessible(true);
            return (List<RouteListener>) m.invoke(ctx);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void emitRoute(FlashContext ctx, HttpMethod method, String path, String namespace,
                                  Class<? extends RequestHandler> handlerClass) {
        RouteEvent event = new RouteEvent(method, path, namespace, "FlashApp", handlerClass, List.of());
        for (RouteListener listener : listeners(ctx)) listener.onRoute(event);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static final class TestRegistrar extends FlashRegistrar<TestRegistrar> {
        private final FlashContext ctx;
        private final Map<String, RequestHandler> routes = new HashMap<>();
        private final List<MiddlewareNode> middlewares = new ArrayList<>();

        private TestRegistrar(FlashContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public FlashContext ctx() {
            return ctx;
        }

        @Override
        protected void addRoute(HttpMethod method, String path, RequestHandler handler, List<MiddlewareNode> mw) {
            routes.put(method.name() + " " + path, handler);
        }

        @Override
        protected void addWsRoute(String path, WebSocketEndpoint endpoint) {

        }

        @Override
        protected void addMiddleware(MiddlewareNode mw) {
            middlewares.add(mw);
        }

        RequestHandler route(HttpMethod method, String path) {
            return routes.get(method.name() + " " + path);
        }
    }
}
