package dev.relism.flash.ext.view.jte;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.GET;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JteTargetResolverTest {

    @GET("/home")
    @Template("pages/home.jte")
    static class TemplateHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @Template("pages/no-route.jte")
    static class NoRouteHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/empty")
    @Template("   ")
    static class EmptyTemplateHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @Test
    void resolve_template_returnsTarget_withMirroredHttpContentType() {
        JteSettings htmlSettings = JteSettings.builder().contentType(gg.jte.ContentType.Html).build();
        JteSettings plainSettings = JteSettings.builder().contentType(gg.jte.ContentType.Plain).build();

        JteTarget html = JteTargetResolver.resolve(TemplateHandler.class, htmlSettings);
        JteTarget plain = JteTargetResolver.resolve(TemplateHandler.class, plainSettings);

        assertNotNull(html);
        assertEquals("pages/home.jte", html.template());
        assertEquals(ContentType.TEXT_HTML, html.contentType());
        assertEquals(ContentType.TEXT_PLAIN, plain.contentType());
    }

    @Test
    void resolve_withoutTemplate_returnsNull() {
        assertNull(JteTargetResolver.resolve(RequestHandler.class, JteSettings.builder().build()));
    }

    @Test
    void resolve_templateWithoutRoute_fails() {
        assertThrows(IllegalStateException.class,
                () -> JteTargetResolver.resolve(NoRouteHandler.class, JteSettings.builder().build()));
    }

    @Test
    void resolve_emptyTemplate_fails() {
        assertThrows(IllegalStateException.class,
                () -> JteTargetResolver.resolve(EmptyTemplateHandler.class, JteSettings.builder().build()));
    }
}
