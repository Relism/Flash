package dev.relism.flash.models;

import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.Inject;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.routing.routers.fastpathrouter.FastPathViews;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestHandlerTest {

    static final class Injected extends RequestHandler {
        @Inject private String service;
        @Override public Object handle(Request request, Response response) { return service; }
    }

    static final class Missing extends RequestHandler {
        @Inject private Integer absent;
        @Override public Object handle(Request request, Response response) { return absent; }
    }

    static final class Shared extends RequestHandler {
        @Inject private static String service;
        @Override public Object handle(Request request, Response response) { return service; }
    }

    static final class Frozen extends RequestHandler {
        @Inject private final String service = "";
        @Override public Object handle(Request request, Response response) { return service; }
    }

    @Test
    void an_injected_field_is_filled_before_the_handler_runs() throws Exception {
        Injected handler = new Injected();
        handler.bind(context());

        assertEquals("provided", handler.handle(request(), new Response(200, ContentType.NONE)));
    }

    @Test
    void a_static_or_final_field_is_refused() {
        FlashContext ctx = context();

        assertTrue(assertThrows(IllegalStateException.class, () -> new Shared().bind(ctx))
                .getMessage().contains("is static"));
        assertTrue(assertThrows(IllegalStateException.class, () -> new Frozen().bind(ctx))
                .getMessage().contains("is final"));
    }

    @Test
    void a_field_nothing_provides_fails_the_boot_naming_it() {
        FlashContext ctx = new FlashContext();
        ctx.complete();

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> new Missing().bind(ctx));

        assertTrue(refused.getMessage().contains("Missing.absent"));
    }

    private static FlashContext context() {
        FlashContext ctx = new FlashContext();
        ctx.provide(String.class, "provided");
        ctx.complete();
        return ctx;
    }

    private static Request request() {
        return new Request(new RequestLine(HttpMethod.GET,
                new FastPathViews.StringByteView("/"), null,
                new FastPathViews.StringByteView("HTTP/1.1"), new Http1HeaderMap()),
                new byte[0]);
    }
}
