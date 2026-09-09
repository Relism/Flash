package dev.relism.flash.routing;

import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.models.SimpleHandler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AbstractRouterTest {

    static class DummyRouter extends AbstractRouter {
        RequestHandler lastAddedHandler;
        HttpMethod     lastAddedMethod;
        String         lastAddedPath;

        @Override
        public RequestHandler route(Request request, Object scratch) { return null; }

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
        Route ann = Routes.of(ProfileHandler.class);

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


    /**
     * HttpException carries the status the caller meant. Before this was honoured every one of
     * them came back as 500, including the 400s RequestHelper and flash-ext-jackson raise.
     */
    @Test
    void defaultExceptionHandlerHonoursHttpExceptionStatus() throws Exception {
        DummyRouter router = new DummyRouter();
        Response res = new Response(200, ContentType.TEXT_PLAIN);

        Object body = router.getExceptionHandler()
                .handle(HttpException.badRequest("bad \"input\""), null, res);

        assertEquals(400, res.getStatusCode());
        String rendered = new String((byte[]) body, StandardCharsets.UTF_8);
        assertTrue(rendered.contains("\\\"input\\\""), "message must be JSON-escaped: " + rendered);
        assertTrue(rendered.contains("\"status\":400"), rendered);
    }
}
