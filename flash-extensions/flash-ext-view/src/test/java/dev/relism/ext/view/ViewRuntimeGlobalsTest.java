package dev.relism.ext.view;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewRuntimeGlobalsTest {

    private static final GlobalBinding APP = new GlobalBinding("appName", req -> "flash");
    private static final GlobalBinding PATH = new GlobalBinding("requestPath", req -> "/x");

    @Test
    void merge_globalsAreNestedUnderReservedNamespace() {
        ViewRuntime runtime = new ViewRuntime(new NoopAdapter(), List.of(
                APP,
                PATH
        ));

        ViewModel merged = runtime.merge(null, ViewModel.of("title", "dashboard"));

        Map<?, ?> global = (Map<?, ?>) merged.toMap().get("global");
        assertEquals("flash", global.get("appName"));
        assertEquals("/x", global.get("requestPath"));
        assertEquals("dashboard", merged.toMap().get("title"));
    }

    @Test
    void merge_nullLocal_keepsGlobals() {
        ViewRuntime runtime = new ViewRuntime(new NoopAdapter(), List.of(
                new GlobalBinding("signedIn", req -> true)
        ));

        ViewModel merged = runtime.merge(null, null);

        Map<?, ?> global = (Map<?, ?>) merged.toMap().get("global");
        assertEquals(true, global.get("signedIn"));
    }

    @Test
    void merge_withoutGlobals_returnsCopyOfLocal() {
        ViewRuntime runtime = new ViewRuntime(new NoopAdapter(), List.of());
        ViewModel local = ViewModel.of("k", "v");

        ViewModel merged = runtime.merge(null, local);

        assertEquals("v", merged.toMap().get("k"));
    }

    @Test
    void merge_withNullGlobalsAndNullLocal_returnsEmpty() {
        ViewRuntime runtime = new ViewRuntime(new NoopAdapter(), List.of());

        ViewModel merged = runtime.merge(null, null);

        assertTrue(merged.toMap().isEmpty());
    }

    @Test
    void merge_localGlobalNamespace_throws() {
        ViewRuntime runtime = new ViewRuntime(new NoopAdapter(), List.of(APP));

        assertThrows(IllegalStateException.class,
                () -> runtime.merge(null, ViewModel.of("global", Map.of("x", 1))));
    }

    @Test
    void resolve_withoutViewAnnotation_failsFast() {
        ViewRuntime runtime = new ViewRuntime(new NoopAdapter(), List.of());

        assertThrows(IllegalStateException.class,
                () -> runtime.resolve(NoViewHandler.class));
    }

    @Test
    void render_prefersAdapterContentTypeWhenProvided() throws Exception {
        ViewRuntime runtime = new ViewRuntime(new PlainTextAdapter(), List.of());
        ViewTargetResolver.ResolvedView resolved = new ViewTargetResolver.ResolvedView(
                new ViewTarget(ViewKind.PAGE, "pages/home", ""),
                dev.relism.http.ContentType.TEXT_HTML
        );
        dev.relism.models.Response res = new dev.relism.models.Response(200, dev.relism.http.ContentType.JSON);

        runtime.render(resolved, null, res, ViewModel.of("a", 1));

        assertEquals(new String(dev.relism.http.ContentType.TEXT_PLAIN.getBytes()), new String(res.getContentType()));
    }

    @Test
    void render_usesResolvedDefaultContentTypeWhenAdapterOmitsIt() throws Exception {
        ViewRuntime runtime = new ViewRuntime(new NoTypeAdapter(), List.of());
        ViewTargetResolver.ResolvedView resolved = new ViewTargetResolver.ResolvedView(
                new ViewTarget(ViewKind.PAGE, "pages/home", ""),
                dev.relism.http.ContentType.TEXT_HTML
        );
        dev.relism.models.Response res = new dev.relism.models.Response(200, dev.relism.http.ContentType.JSON);

        runtime.render(resolved, null, res, ViewModel.of("a", 1));

        assertEquals(new String(dev.relism.http.ContentType.TEXT_HTML.getBytes()), new String(res.getContentType()));
    }

    static final class NoViewHandler extends dev.relism.models.RequestHandler {
        @Override
        public Object handle(dev.relism.models.Request request, dev.relism.models.Response response) {
            return null;
        }
    }

    private static final class NoopAdapter implements ViewEngineAdapter {
        @Override
        public EngineCapabilities capabilities() {
            return EngineCapabilities.NONE;
        }

        @Override
        public RenderOutput render(ViewTarget target, java.util.Map<String, Object> model,
                                   dev.relism.models.Request req,
                                   dev.relism.models.Response res) {
            return RenderOutput.html("");
        }
    }

    private static final class NoTypeAdapter implements ViewEngineAdapter {
        @Override
        public EngineCapabilities capabilities() {
            return EngineCapabilities.NONE;
        }

        @Override
        public RenderOutput render(ViewTarget target, java.util.Map<String, Object> model,
                                   dev.relism.models.Request req,
                                   dev.relism.models.Response res) {
            return new RenderOutput("", null);
        }
    }

    private static final class PlainTextAdapter implements ViewEngineAdapter {
        @Override
        public EngineCapabilities capabilities() {
            return EngineCapabilities.NONE;
        }

        @Override
        public RenderOutput render(ViewTarget target, java.util.Map<String, Object> model,
                                   dev.relism.models.Request req,
                                   dev.relism.models.Response res) {
            return new RenderOutput("", dev.relism.http.ContentType.TEXT_PLAIN);
        }
    }
}
