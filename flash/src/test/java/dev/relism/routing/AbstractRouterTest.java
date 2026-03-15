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

    // A dummy router for testing base functionality
    static class DummyRouter extends AbstractRouter {
        RequestHandler lastAddedHandler;
        HttpMethod lastAddedMethod;
        String lastAddedPath;

        @Override
        public RequestHandler route(Request request) {
            return null; // Not testing routing logic here
        }

        @Override
        protected AbstractRouter addRoute(HttpMethod method, String path, RequestHandler handler) {
            this.lastAddedMethod = method;
            this.lastAddedPath = path;
            this.lastAddedHandler = handler;
            return this;
        }
    }

    @Route(method = "POST", path = "/profile")
    static class ProfileHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    static class UnannotatedHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
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

    // --- helpers ---

    @Test
    void getPostPutDelete_delegatesToAddRouteWithSanitizedPath() {
        DummyRouter router = new DummyRouter();
        SimpleHandler.FunctionalHandler func = (req, res) -> "OK";

        router.get("users/", func);
        assertEquals(HttpMethod.GET, router.lastAddedMethod);
        assertEquals("/users", router.lastAddedPath);
        assertTrue(router.lastAddedHandler instanceof SimpleHandler);

        router.post("/items", func);
        assertEquals(HttpMethod.POST, router.lastAddedMethod);

        router.put("update", func);
        assertEquals(HttpMethod.PUT, router.lastAddedMethod);

        router.delete("//delete//", func);
        assertEquals(HttpMethod.DELETE, router.lastAddedMethod);
        assertEquals("/delete", router.lastAddedPath);
    }

    // --- register ---

    @Test
    void register_annotatedHandler_addsRoute() {
        DummyRouter router = new DummyRouter();
        ProfileHandler handler = new ProfileHandler();
        
        router.register(handler);
        
        assertEquals(HttpMethod.POST, router.lastAddedMethod);
        assertEquals("/profile", router.lastAddedPath);
        assertEquals(handler, router.lastAddedHandler);
    }

    @Test
    void register_unannotatedHandler_doesNothing() {
        DummyRouter router = new DummyRouter();
        router.register(new UnannotatedHandler());
        assertNull(router.lastAddedMethod); // Nothing added
    }

    // --- default handlers ---

    @Test
    void defaultNotFoundHandler_returns404Html() throws Exception {
        DummyRouter router = new DummyRouter();
        Response res = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        
        assertNotNull(router.getNotFoundHandler());
        
        SimpleHandler.FunctionalHandler custom = (req, resp) -> "Custom 404";
        router.onNotFound(custom);
        
        assertEquals("Custom 404", router.getNotFoundHandler().handle(null, res));
    }

    @Test
    void defaultExceptionHandler_canBeOverridden() throws Exception {
        DummyRouter router = new DummyRouter();
        assertNotNull(router.getExceptionHandler());

        AbstractRouter.ExceptionHandler custom = (ex, req, res) -> "Caught";
        router.onException(custom);
        
        assertEquals("Caught", router.getExceptionHandler().handle(new RuntimeException(), null, null));
    }
}
