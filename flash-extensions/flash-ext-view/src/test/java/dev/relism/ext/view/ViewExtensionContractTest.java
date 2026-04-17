package dev.relism.ext.view;

import dev.relism.extension.FlashContext;
import dev.relism.extension.AnnotationProcessor;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.Response;
import dev.relism.routing.GET;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViewExtensionContractTest {

    @GET("/plain")
    @Page("pages/plain")
    static final class PlainPageHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/view")
    @Page("pages/view")
    static final class ViewPageHandler extends ViewHandler {
        @Override
        public ViewModel render(Request req) {
            return ViewModel.empty();
        }
    }

    @Test
    void processor_rejectsViewAnnotationOnNonViewHandler() {
        FlashContext ctx = new FlashContext();
        new ViewExtension(new NoopAdapter()).provide(ctx);

        assertThrows(IllegalStateException.class,
                () -> processors(ctx).forEach(p -> p.process(PlainPageHandler.class)));
    }

    @Test
    void processor_acceptsViewHandlerWithViewAnnotation() {
        FlashContext ctx = new FlashContext();
        new ViewExtension(new NoopAdapter()).provide(ctx);

        assertDoesNotThrow(() -> processors(ctx).forEach(p -> p.process(ViewPageHandler.class)));
    }

    @Test
    void constructor_withEngineType_buildsAndProvidesRuntime() {
        FlashContext ctx = new FlashContext();
        ViewExtension extension = new ViewExtension(ViewEngineType.THYMELEAF);

        extension.provide(ctx);

        assertNotNull(ctx.require(ViewRuntime.class));
        assertNotNull(ctx.require(ViewEngineAdapter.class));
    }

    @Test
    void addGlobal_nullResolver_throws() {
        ViewExtension extension = new ViewExtension(new NoopAdapter());
        assertThrows(NullPointerException.class, () -> extension.addGlobal("appName", null));
    }

    @Test
    void addGlobal_invalidKey_throws() {
        ViewExtension extension = new ViewExtension(new NoopAdapter());
        assertThrows(IllegalArgumentException.class, () -> extension.addGlobal("", req -> "x"));
        assertThrows(IllegalArgumentException.class, () -> extension.addGlobal("global", req -> "x"));
        assertThrows(IllegalArgumentException.class, () -> extension.addGlobal("a.b", req -> "x"));
    }

    @Test
    void constructor_withNullAdapter_throws() {
        assertThrows(NullPointerException.class, () -> new ViewExtension((ViewEngineAdapter) null));
    }

    @SuppressWarnings("unchecked")
    private static List<AnnotationProcessor> processors(FlashContext ctx) {
        try {
            Method m = FlashContext.class.getDeclaredMethod("processors");
            m.setAccessible(true);
            return ((List<AnnotationProcessor>) m.invoke(ctx)).stream()
                    .filter(p -> p.getClass().getName().contains("ViewExtension"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class NoopAdapter implements ViewEngineAdapter {
        @Override
        public EngineCapabilities capabilities() {
            return new EngineCapabilities(true);
        }

        @Override
        public RenderOutput render(ViewTarget target, java.util.Map<String, Object> model,
                                   Request req,
                                   Response res) {
            return RenderOutput.html("");
        }
    }
}
