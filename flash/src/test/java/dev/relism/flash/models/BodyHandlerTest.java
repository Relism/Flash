package dev.relism.flash.models;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.routing.routers.fastpathrouter.FastPathViews;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BodyHandlerTest {

    record Payload(String text) {}

    static abstract class TextHandler<B> extends BodyHandler<B> {
        @Override protected B body(Request request) {
            return read(new String(request.body().bytes(), StandardCharsets.UTF_8));
        }
        protected abstract B read(String text);
    }

    static final class Echo extends TextHandler<Payload> {
        @Override protected Payload read(String text) { return new Payload(text); }
        @Override protected Object handle(Request request, Response response, Payload body) { return body.text(); }
    }

    static final class Raw extends BodyHandler<Object> {
        @Override protected Object body(Request request) { return null; }
        @Override protected Object handle(Request request, Response response, Object body) { return null; }
    }

    @Test
    void theBodyTypeIsReadThroughTheWholeChain() {
        assertEquals(Payload.class, BodyHandler.bodyTypeOf(Echo.class), "resolved past the intermediate base");
        assertEquals(Object.class, BodyHandler.bodyTypeOf(Raw.class));
        assertNull(BodyHandler.bodyTypeOf(RequestHandler.class), "a handler with no body declares none");
    }

    @Test
    void theBodyIsReadBeforeTheHandlerSeesIt() throws Exception {
        Object answer = new Echo().handle(request("hello"), new Response(200, ContentType.NONE));

        assertEquals("hello", answer);
    }

    static final class Injected extends RequestHandler {
        @dev.relism.flash.extension.Inject private String service;
        @Override public Object handle(Request request, Response response) { return service; }
    }

    static final class Missing extends RequestHandler {
        @dev.relism.flash.extension.Inject private Integer absent;
        @Override public Object handle(Request request, Response response) { return absent; }
    }

    @Test
    void an_injected_field_is_filled_before_the_handler_runs() throws Exception {
        dev.relism.flash.extension.FlashContext ctx = new dev.relism.flash.extension.FlashContext();
        ctx.provide(String.class, "provided");
        ctx.complete();

        Injected handler = new Injected();
        handler.bind(ctx);

        assertEquals("provided", handler.handle(request(""), new Response(200, ContentType.NONE)));
    }

    static final class Shared extends RequestHandler {
        @dev.relism.flash.extension.Inject private static String service;
        @Override public Object handle(Request request, Response response) { return service; }
    }

    static final class Frozen extends RequestHandler {
        @dev.relism.flash.extension.Inject private final String service = "";
        @Override public Object handle(Request request, Response response) { return service; }
    }

    @Test
    void a_static_or_final_field_is_refused() {
        dev.relism.flash.extension.FlashContext ctx = new dev.relism.flash.extension.FlashContext();
        ctx.provide(String.class, "provided");
        ctx.complete();

        org.junit.jupiter.api.Assertions.assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> new Shared().bind(ctx)).getMessage().contains("is static"));
        org.junit.jupiter.api.Assertions.assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> new Frozen().bind(ctx)).getMessage().contains("is final"));
    }

    @Test
    void a_field_nothing_provides_fails_the_boot_naming_it() {
        dev.relism.flash.extension.FlashContext ctx = new dev.relism.flash.extension.FlashContext();
        ctx.complete();

        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> new Missing().bind(ctx));

        org.junit.jupiter.api.Assertions.assertTrue(refused.getMessage().contains("Missing.absent"));
    }

    private static Request request(String body) {
        return new Request(new RequestLine(HttpMethod.POST,
                new FastPathViews.StringByteView("/echo"), null,
                new FastPathViews.StringByteView("HTTP/1.1"), new Http1HeaderMap()),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
