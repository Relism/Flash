package dev.relism.flash.ext.view.core;

import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.models.RequestHandler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BaseViewExtensionTest {

    private static final class FakeViewExtension extends BaseViewExtension<Object> {
        @Override
        protected ViewRuntimeBridge<Object> createRuntime(List<GlobalValue> globals) {
            return new ViewRuntimeBridge<>() {
                @Override public Object resolve(Class<?> handlerClass) { return null; }
                @Override public RenderedView render(BaseViewHandler<Object> handler, Object target,
                                                       dev.relism.flash.models.Request req,
                                                       dev.relism.flash.models.Response res) { return null; }
            };
        }

        @Override
        protected void validateHandlerClass(Class<? extends RequestHandler> handlerClass) {}
    }

    @Test
    void configure_twoViewEngines_failsWithClearMessage() {
        FlashContext ctx = new FlashContext();
        new FakeViewExtension().configure(null, ctx);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new FakeViewExtension().configure(null, ctx));

        assertTrue(ex.getMessage().contains("Only one view engine"),
                "expected a clear cross-engine message, got: " + ex.getMessage());
    }
}
