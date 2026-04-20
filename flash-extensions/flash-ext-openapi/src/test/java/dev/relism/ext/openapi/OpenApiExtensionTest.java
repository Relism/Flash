package dev.relism.ext.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.extension.FlashContext;
import dev.relism.extension.FlashRegistrar;
import dev.relism.extension.RouteEvent;
import dev.relism.extension.RouteListener;
import dev.relism.http.ContentType;
import dev.relism.http.HttpMethod;
import dev.relism.models.RequestHandler;
import dev.relism.models.Response;
import dev.relism.routing.GET;
import dev.relism.routing.Middleware;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiExtensionTest {

    @GET("/health")
    @ApiOperation(summary = "Health check")
    @APIResponse(responseCode = "200", description = "OK")
    static class HealthHandler extends RequestHandler {
        @Override
        public Object handle(dev.relism.models.Request request, Response response) {
            return "ok";
        }
    }

    @Test
    void provide_collects_operations_and_routes_serve_json_yaml_swagger() throws Exception {
        FlashContext ctx = new FlashContext();
        OpenApiExtension ext = new OpenApiExtension("/docs", "My API", "2.0.0", "desc");
        ext.provide(ctx);

        emitRoute(ctx, HttpMethod.GET, "/health", "/", HealthHandler.class);

        TestRegistrar app = new TestRegistrar(ctx);
        ext.routes(app, ctx);

        assertNotNull(app.route(HttpMethod.GET, "/docs.json"));
        assertNotNull(app.route(HttpMethod.GET, "/docs.yaml"));
        assertNotNull(app.route(HttpMethod.GET, "/docs/swagger"));

        Response jsonRes = new Response(200, ContentType.NONE);
        Object jsonBody = app.route(HttpMethod.GET, "/docs.json").handle(null, jsonRes);
        assertEquals(new String(ContentType.JSON.getBytes()), new String(jsonRes.getContentType()));
        assertTrue(String.valueOf(jsonBody).contains("\"openapi\":\"3.0.3\""));
        assertTrue(String.valueOf(jsonBody).contains("\"title\":\"My API\""));

        Response yamlRes = new Response(200, ContentType.NONE);
        Object yamlBody = app.route(HttpMethod.GET, "/docs.yaml").handle(null, yamlRes);
        assertEquals("application/yaml", new String(yamlRes.getContentType()));
        assertTrue(String.valueOf(yamlBody).contains("openapi: \"3.0.3\""));

        Response swaggerRes = new Response(200, ContentType.NONE);
        Object swaggerBody = app.route(HttpMethod.GET, "/docs/swagger").handle(null, swaggerRes);
        assertEquals(new String(ContentType.TEXT_HTML.getBytes()), new String(swaggerRes.getContentType()));
        assertTrue(String.valueOf(swaggerBody).contains("SwaggerUIBundle"));
        assertTrue(String.valueOf(swaggerBody).contains("/docs.json"));
    }

    @Test
    void routes_use_mapper_from_context_when_provided() throws Exception {
        FlashContext ctx = new FlashContext();
        OpenApiExtension ext = new OpenApiExtension();
        ObjectMapper mapper = new ObjectMapper();
        ctx.provide(ObjectMapper.class, mapper);

        ext.provide(ctx);
        TestRegistrar app = new TestRegistrar(ctx);
        ext.routes(app, ctx);

        Response jsonRes = new Response(200, ContentType.NONE);
        Object jsonBody = app.route(HttpMethod.GET, "/openapi.json").handle(null, jsonRes);
        assertTrue(String.valueOf(jsonBody).contains("\"openapi\":\"3.0.3\""));
    }

    @GET("/users")
    @ApiOperation(summary = "Scoped users")
    static class ScopedUsersHandler extends RequestHandler {
        @Override
        public Object handle(dev.relism.models.Request request, Response response) {
            return List.of();
        }
    }

    @Test
    void collects_full_runtime_path_from_route_event() {
        FlashContext ctx = new FlashContext();
        OpenApiExtension ext = new OpenApiExtension();
        ext.provide(ctx);

        emitRoute(ctx, HttpMethod.GET, "/api/v1/users", "/api/v1", ScopedUsersHandler.class);

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
        ext.provide(ctx);

        emitRoute(ctx, HttpMethod.GET, "//blogs", "/", ScopedUsersHandler.class);

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
        private final List<Middleware> middlewares = new ArrayList<>();

        private TestRegistrar(FlashContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public FlashContext ctx() {
            return ctx;
        }

        @Override
        protected void addRoute(HttpMethod method, String path, RequestHandler handler, List<Middleware> mw) {
            routes.put(method.name() + " " + path, handler);
        }

        @Override
        protected void addMiddleware(Middleware mw) {
            middlewares.add(mw);
        }

        RequestHandler route(HttpMethod method, String path) {
            return routes.get(method.name() + " " + path);
        }
    }
}
