package dev.relism.routing;

import dev.relism.http.ContentType;
import dev.relism.http.HttpMethod;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.Response;
import dev.relism.models.SimpleHandler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AbstractRouterTest {

    // A minimal concrete router for testing base-class functionality
    static class DummyRouter extends AbstractRouter {
        RequestHandler lastAddedHandler;
        HttpMethod     lastAddedMethod;
        String         lastAddedPath;

        @Override
        public RequestHandler route(Request request) {
            return null;
        }

        @Override
        protected AbstractRouter addRoute(HttpMethod method, String path, RequestHandler handler) {
            this.lastAddedMethod  = method;
            this.lastAddedPath    = path;
            this.lastAddedHandler = handler;
            return this;
        }
    }

    // --- namespace ---

    @Test
    void setNamespace_updatesStringAndBytes() {
        DummyRouter router = new DummyRouter();
        assertEquals("/", router.getNamespace());

        router.setNamespace("/api");
        assertEquals("/api", router.getNamespace());
        assertArrayEquals("/api".getBytes(StandardCharsets.UTF_8), router.getNamespaceBytes());
    }

    // --- doRegister (infrastructure method used by FlashApp/FlashScope) ---

    @Test
    void doRegister_lambda_sanitizesPathAndWrapsHandler() {
        DummyRouter router = new DummyRouter();
        SimpleHandler.FunctionalHandler func = (req, res) -> "OK";

        router.doRegister(HttpMethod.GET, "users/", func, new Middleware[0]);
        assertEquals(HttpMethod.GET, router.lastAddedMethod);
        assertEquals("/users", router.lastAddedPath);
        assertNotNull(router.lastAddedHandler);

        router.doRegister(HttpMethod.DELETE, "//delete//", func, new Middleware[0]);
        assertEquals(HttpMethod.DELETE, router.lastAddedMethod);
        assertEquals("/delete", router.lastAddedPath);
    }

    @Route(method = HttpMethod.POST, path = "/profile")
    static class ProfileHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) { return null; }
    }

    static class UnannotatedHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) { return null; }
    }

    @Test
    void doRegister_annotatedHandler_addsRoute() {
        DummyRouter router = new DummyRouter();
        ProfileHandler handler = new ProfileHandler();

        router.doRegister(handler, new Middleware[0]);

        assertEquals(HttpMethod.POST, router.lastAddedMethod);
        assertEquals("/profile", router.lastAddedPath);
        assertEquals(handler, router.lastAddedHandler);
    }

    @Test
    void doRegister_unannotatedHandler_doesNothing() {
        DummyRouter router = new DummyRouter();
        router.doRegister(new UnannotatedHandler(), new Middleware[0]);
        assertNull(router.lastAddedMethod);
    }

    // --- error handlers ---

    @Test
    void defaultNotFoundHandler_returns404Html() throws Exception {
        DummyRouter router = new DummyRouter();
        Response res = new Response(200, new byte[0], ContentType.TEXT_PLAIN);

        assertNotNull(router.getNotFoundHandler());

        router.onNotFound((req, resp) -> "Custom 404");
        assertEquals("Custom 404", router.getNotFoundHandler().handle(null, res));
    }

    @Test
    void defaultExceptionHandler_canBeOverridden() throws Exception {
        DummyRouter router = new DummyRouter();
        assertNotNull(router.getExceptionHandler());

        router.onException((ex, req, res) -> "Caught");
        assertEquals("Caught", router.getExceptionHandler().handle(new RuntimeException(), null, null));
    }
}
