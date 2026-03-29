package dev.relism.routing;

import dev.relism.http.ContentType;
import dev.relism.http.HttpMethod;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.Response;
import dev.relism.models.SimpleHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AbstractRouterTest {

    static class DummyRouter extends AbstractRouter {
        RequestHandler lastAddedHandler;
        HttpMethod     lastAddedMethod;
        String         lastAddedPath;

        @Override
        public RequestHandler route(Request request) { return null; }

        @Override
        protected AbstractRouter addRoute(HttpMethod method, String path, RequestHandler handler) {
            this.lastAddedMethod  = method;
            this.lastAddedPath    = path;
            this.lastAddedHandler = handler;
            return this;
        }
    }

    @Test
    void doRegister_sanitizesPathAndWrapsHandler() {
        DummyRouter router = new DummyRouter();
        router.doRegister(HttpMethod.GET, "users/", new SimpleHandler((req, res) -> "OK"), new Middleware[0]);
        assertEquals(HttpMethod.GET, router.lastAddedMethod);
        assertEquals("/users", router.lastAddedPath);
        assertNotNull(router.lastAddedHandler);

        router.doRegister(HttpMethod.DELETE, "//delete//", new SimpleHandler((req, res) -> "OK"), new Middleware[0]);
        assertEquals(HttpMethod.DELETE, router.lastAddedMethod);
        assertEquals("/delete", router.lastAddedPath);
    }

    @Route(method = HttpMethod.POST, path = "/profile")
    static class ProfileHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) { return null; }
    }

    @Test
    void doRegister_classBasedHandler_addsRoute() {
        DummyRouter router = new DummyRouter();
        ProfileHandler handler = new ProfileHandler();
        Route ann = ProfileHandler.class.getAnnotation(Route.class);

        router.doRegister(ann.method(), ann.path(), handler, new Middleware[0]);

        assertEquals(HttpMethod.POST, router.lastAddedMethod);
        assertEquals("/profile", router.lastAddedPath);
        assertEquals(handler, router.lastAddedHandler);
    }

    @Test
    void defaultNotFoundHandler_returns404() throws Exception {
        DummyRouter router = new DummyRouter();
        assertNotNull(router.getNotFoundHandler());

        router.onNotFound((req, resp) -> "Custom 404");
        assertEquals("Custom 404", router.getNotFoundHandler().handle(null, null));
    }

    @Test
    void defaultExceptionHandler_canBeOverridden() {
        DummyRouter router = new DummyRouter();
        assertNotNull(router.getExceptionHandler());

        router.onException((ex, req, res) -> "Caught");
        assertEquals("Caught", router.getExceptionHandler().handle(new RuntimeException(), null, null));
    }
}
