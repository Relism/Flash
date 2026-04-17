package dev.relism.ext.view;

import dev.relism.extension.FlashContext;
import dev.relism.http.ContentType;
import dev.relism.models.Request;
import dev.relism.models.Response;
import dev.relism.routing.GET;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewHandlerLifecycleTest {

    @GET("/lifecycle")
    @Page("pages/home")
    static final class LifecycleHandler extends ViewHandler {
        boolean onViewInitCalled;
        DummyService service;

        @Override
        protected void onViewInit() {
            onViewInitCalled = true;
            service = require(DummyService.class);
        }

        @Override
        public ViewModel render(Request req) {
            return ViewModel.of("title", service.value());
        }
    }

    static final class DummyService {
        String value() { return "ok"; }
    }

    @Test
    void onInit_resolvesRuntime_and_onViewInit_runs_once() {
        FlashContext ctx = new FlashContext();
        ctx.provide(DummyService.class, new DummyService());
        ctx.provide(ViewRuntime.class, new ViewRuntime(new EchoAdapter(), List.of()));

        LifecycleHandler handler = new LifecycleHandler();
        handler.bind(ctx);

        assertTrue(handler.onViewInitCalled);
        assertEquals("ok", handler.service.value());
    }

    @Test
    void handle_rendersThroughRuntime() throws Exception {
        FlashContext ctx = new FlashContext();
        ctx.provide(DummyService.class, new DummyService());
        ctx.provide(ViewRuntime.class, new ViewRuntime(new EchoAdapter(), List.of()));

        LifecycleHandler handler = new LifecycleHandler();
        handler.bind(ctx);
        Response res = new Response(200, ContentType.JSON);

        Object out = handler.handle(null, res);

        assertEquals("ok", out);
        assertEquals(new String(ContentType.TEXT_HTML.getBytes()), new String(res.getContentType()));
    }

    private static final class EchoAdapter implements ViewEngineAdapter {
        @Override
        public EngineCapabilities capabilities() {
            return EngineCapabilities.NONE;
        }

        @Override
        public RenderOutput render(ViewTarget target, java.util.Map<String, Object> model,
                                   Request req,
                                   Response res) {
            return RenderOutput.html(String.valueOf(model.get("title")));
        }
    }
}
