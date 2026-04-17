package dev.relism.ext.view;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewRuntimeGlobalsTest {

    @Test
    void merge_globalsThenLocal_localWins() {
        ViewRuntime runtime = new ViewRuntime(new NoopAdapter(), List.of(
                req -> ViewModel.of("nav", "global").with("app", "flash"),
                req -> ViewModel.of("nav", "global-2")
        ));

        ViewModel merged = runtime.merge(null, ViewModel.of("nav", "local"));

        assertEquals("local", merged.toMap().get("nav"));
        assertEquals("flash", merged.toMap().get("app"));
    }

    @Test
    void merge_nullLocal_keepsGlobals() {
        ViewRuntime runtime = new ViewRuntime(new NoopAdapter(), List.of(
                req -> ViewModel.of("signedIn", true)
        ));

        ViewModel merged = runtime.merge(null, null);

        assertEquals(true, merged.toMap().get("signedIn"));
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
}
