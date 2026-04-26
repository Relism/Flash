package dev.relism.flash.ext.view.jte;

import dev.relism.flash.ext.view.core.ViewModel;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.GET;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JteExtensionTest {

    @GET("/ok")
    @Template("pages/home.jte")
    static class ValidHandler extends JteHandler {
        @Override
        public ViewModel render(Request req) {
            return ViewModel.empty();
        }
    }

    @GET("/wrong")
    @Template("pages/home.jte")
    static class WrongBaseHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/missing")
    static class MissingTemplateHandler extends JteHandler {}

    @Test
    void constructor_requiresNonNullCustomizer() {
        assertThrows(NullPointerException.class, () -> new JteExtension(null));
    }

    @Test
    void validate_acceptsProperHandler() throws Exception {
        JteExtension ext = new JteExtension();
        invokeValidate(ext, ValidHandler.class);
    }

    @Test
    void validate_rejectsTemplateWithoutJteHandlerBase() {
        JteExtension ext = new JteExtension();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> invokeValidate(ext, WrongBaseHandler.class));

        assertTrue(ex.getMessage().contains("does not extend JteHandler"));
    }

    @Test
    void validate_rejectsJteHandlerWithoutTemplate() {
        JteExtension ext = new JteExtension();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> invokeValidate(ext, MissingTemplateHandler.class));

        assertTrue(ex.getMessage().contains("must declare @Template"));
    }

    @Test
    void fluent_static_settings_are_applied() {
        JteExtension ext = new JteExtension()
                .templateRoot("templates")
                .serveStatics(false)
                .staticPrefix("assets")
                .withStaticCors()
                .staticCors(cfg -> cfg
                        .staticCorsAllowOrigin("https://cdn.example.com"));

        JteSettings settings = readSettings(ext);
        assertEquals("/templates", settings.templateRoot());
        assertFalse(settings.serveStatics());
        assertEquals("/assets", settings.staticPrefix());
        assertTrue(settings.staticCorsEnabled());
        assertEquals("https://cdn.example.com", settings.staticCorsAllowOrigin());
    }

    @Test
    void routes_register_static_wildcard_when_enabled() {
        JteExtension ext = new JteExtension(cfg -> cfg.staticPrefix("/assets"));
        TestRegistrar app = new TestRegistrar();
        ext.routes(app, new dev.relism.flash.extension.FlashContext());
        assertTrue(app.routes.containsKey("GET /assets/**"));
        assertTrue(app.routes.containsKey("HEAD /assets/**"));
    }

    @Test
    void routes_do_not_register_static_when_disabled() {
        JteExtension ext = new JteExtension().serveStatics(false);
        TestRegistrar app = new TestRegistrar();
        ext.routes(app, new dev.relism.flash.extension.FlashContext());
        assertTrue(app.routes.isEmpty());
    }

    @Test
    void static_handler_serves_existing_asset() throws Exception {
        JteExtension ext = new JteExtension(cfg -> cfg
                .staticPrefix("/assets")
                .largeFileThresholdBytes(1));
        TestRegistrar app = new TestRegistrar();
        ext.routes(app, new dev.relism.flash.extension.FlashContext());

        Request req = request("/assets/sample.css", null, null, null);
        Response res = new Response(200, dev.relism.flash.http.ContentType.TEXT_PLAIN);
        Object out = app.routes.get("GET /assets/**").handle(req, res);

        assertEquals(null, out);
        assertEquals("text/css", new String(res.getContentType(), StandardCharsets.UTF_8));
        assertTrue(res.isStreaming() || res.getBody() != null);
        assertEquals(200, res.getStatusCode());
    }

    private static JteSettings readSettings(JteExtension ext) {
        return ext.getSettings();
    }

    private static void invokeValidate(JteExtension ext, Class<? extends RequestHandler> type) throws Exception {
        Method m = JteExtension.class.getDeclaredMethod("validateHandlerClass", Class.class);
        m.setAccessible(true);
        try {
            m.invoke(ext, type);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
    }

    private static Request request(String path, String query, String acceptEncoding, String origin) {
        dev.relism.flash.models.HeaderMap headers = new dev.relism.flash.models.HeaderMap();
        String raw = "Host: localhost\r\n";
        if (acceptEncoding != null) raw += "Accept-Encoding: " + acceptEncoding + "\r\n";
        if (origin != null) raw += "Origin: " + origin + "\r\n";
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        headers.reset(bytes, 0, bytes.length);

        dev.relism.flash.models.RequestLine line = new dev.relism.flash.models.RequestLine(
                dev.relism.flash.http.HttpMethod.GET,
                new dev.relism.flash.routing.routers.fastpathrouter.FastPathViews.StringByteView(path),
                query == null ? null : new dev.relism.flash.routing.routers.fastpathrouter.FastPathViews.StringByteView(query),
                new dev.relism.flash.routing.routers.fastpathrouter.FastPathViews.StringByteView("HTTP/1.1"),
                headers
        );
        return new Request(line, new byte[0]);
    }

    private static final class TestRegistrar extends dev.relism.flash.extension.FlashRegistrar<TestRegistrar> {
        private final Map<String, RequestHandler> routes = new HashMap<>();
        private final List<dev.relism.flash.routing.Middleware> mws = new ArrayList<>();

        @Override
        public dev.relism.flash.extension.FlashContext ctx() {
            return new dev.relism.flash.extension.FlashContext();
        }

        @Override
        protected void addRoute(dev.relism.flash.http.HttpMethod method, String path, RequestHandler handler, List<dev.relism.flash.routing.Middleware> mw) {
            routes.put(method.name() + " " + path, handler);
        }

        @Override
        protected void addMiddleware(dev.relism.flash.routing.Middleware mw) {
            mws.add(mw);
        }
    }
}
