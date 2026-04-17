package dev.relism.ext.view;

import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.Response;
import dev.relism.routing.GET;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ViewTargetResolverTest {

    @GET("/home")
    @Page("pages/home")
    static class PageHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/partial")
    @Partial(template = "fragments/row", slot = "row")
    static class SlotPartialHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/partial-default-slot")
    @Partial(template = "fragments/card")
    static class DefaultSlotPartialHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/bad")
    @Page("a")
    @Partial(template = "b")
    static class ConflictingHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @Page("pages/no-route")
    static class NoRouteHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @Test
    void resolve_page_returnsPageTarget() {
        ViewTargetResolver.ResolvedView resolved = ViewTargetResolver.resolve(PageHandler.class, new EngineCapabilities(true));

        assertNotNull(resolved);
        assertEquals(ViewKind.PAGE, resolved.target().kind());
        assertEquals("pages/home", resolved.target().template());
    }

    @Test
    void resolve_slotPartial_withoutCapability_failsFast() {
        assertThrows(IllegalStateException.class,
                () -> ViewTargetResolver.resolve(SlotPartialHandler.class, EngineCapabilities.NONE));
    }

    @Test
    void resolve_partial_withoutSlot_defaultsToEmptySlot() {
        ViewTargetResolver.ResolvedView resolved = ViewTargetResolver.resolve(DefaultSlotPartialHandler.class, new EngineCapabilities(true));

        assertNotNull(resolved);
        assertEquals(ViewKind.PARTIAL, resolved.target().kind());
        assertEquals("", resolved.target().slot());
    }

    @GET("/blank-page")
    @Page("   ")
    static class BlankPageHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/blank-partial")
    @Partial(template = "   ")
    static class BlankPartialTemplateHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @Test
    void resolve_multipleViewAnnotations_fails() {
        assertThrows(IllegalStateException.class,
                () -> ViewTargetResolver.resolve(ConflictingHandler.class, new EngineCapabilities(true)));
    }

    @Test
    void resolve_viewAnnotationWithoutRoute_fails() {
        assertThrows(IllegalStateException.class,
                () -> ViewTargetResolver.resolve(NoRouteHandler.class, new EngineCapabilities(true)));
    }

    @Test
    void resolve_blankPageTemplate_fails() {
        assertThrows(IllegalStateException.class,
                () -> ViewTargetResolver.resolve(BlankPageHandler.class, new EngineCapabilities(true)));
    }

    @Test
    void resolve_blankPartialTemplate_fails() {
        assertThrows(IllegalStateException.class,
                () -> ViewTargetResolver.resolve(BlankPartialTemplateHandler.class, new EngineCapabilities(true)));
    }
}
