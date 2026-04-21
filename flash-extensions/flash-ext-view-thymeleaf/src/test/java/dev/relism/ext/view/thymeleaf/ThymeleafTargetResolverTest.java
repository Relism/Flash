package dev.relism.ext.view.thymeleaf;

import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.Response;
import dev.relism.routing.GET;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThymeleafTargetResolverTest {

    @GET("/home")
    @Template("pages/home")
    static class TemplateHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/partial")
    @Fragment(template = "pages/home", value = "rows")
    static class FragmentHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/partial-default")
    @Fragment(template = "pages/home")
    static class FragmentDefaultHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/bad")
    @Template("a")
    @Fragment(template = "b")
    static class ConflictingHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @Template("pages/no-route")
    static class NoRouteHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @Test
    void resolve_template_returnsTemplateTarget() {
        ThymeleafTarget resolved = ThymeleafTargetResolver.resolve(TemplateHandler.class, ThymeleafSettings.builder().build());

        assertNotNull(resolved);
        assertEquals(ThymeleafTarget.Kind.TEMPLATE, resolved.kind());
        assertEquals("pages/home", resolved.template());
    }

    @Test
    void resolve_fragment_withoutValue_usesDefaultFragment() {
        ThymeleafTarget resolved = ThymeleafTargetResolver.resolve(FragmentDefaultHandler.class, ThymeleafSettings.builder().defaultFragment("content").build());

        assertNotNull(resolved);
        assertEquals(ThymeleafTarget.Kind.FRAGMENT, resolved.kind());
        assertEquals("content", resolved.fragment());
    }

    @Test
    void resolve_multipleViewAnnotations_fails() {
        assertThrows(IllegalStateException.class,
                () -> ThymeleafTargetResolver.resolve(ConflictingHandler.class, ThymeleafSettings.builder().build()));
    }

    @Test
    void resolve_viewAnnotationWithoutRoute_fails() {
        assertThrows(IllegalStateException.class,
                () -> ThymeleafTargetResolver.resolve(NoRouteHandler.class, ThymeleafSettings.builder().build()));
    }
}
