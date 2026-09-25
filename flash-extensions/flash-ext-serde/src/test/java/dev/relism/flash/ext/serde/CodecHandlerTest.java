package dev.relism.flash.ext.serde;

import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Http1HeaderMap;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestLine;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.routers.fastpathrouter.FastPathViews;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two codecs, no serialization library anywhere: what this module promises is the pairing itself. */
class CodecHandlerTest {

    record Word(String text) {}
    record Said(String what) {}

    /** Reads a word off the raw body and refuses to write anything. */
    static final class In implements Codec {
        @SuppressWarnings("unchecked")
        @Override public <T> T read(Request request, Type type) {
            assertEquals(Word.class, type, "the codec is told the type the handler named");
            return (T) new Word(new String(request.body().bytes(), StandardCharsets.UTF_8));
        }
        @Override public Object write(Response response, Object value) { throw new UnsupportedOperationException(); }
        @Override public ContentType contentType() { return ContentType.XML; }
    }

    /** Writes, and refuses to read. */
    static final class Out implements Codec {
        @Override public <T> T read(Request request, Type type) { throw new UnsupportedOperationException(); }
        @Override public Object write(Response response, Object value) {
            response.type(ContentType.JSON);
            return "said:" + ((Said) value).what();
        }
        @Override public ContentType contentType() { return ContentType.JSON; }
    }

    static final class Echo extends CodecHandler<In, Word, Out, Said> {
        @Override protected Said handle(Request request, Response response, Word word) { return new Said(word.text()); }
    }

    /** Reads nothing, so it never needs a request codec at all. */
    static final class Answer extends CodecHandler<In, Void, Out, Said> {
        @Override protected Said handle(Request request, Response response, Void nothing) { return new Said("nothing"); }
    }

    @SuppressWarnings("rawtypes")
    static final class Open extends CodecHandler {
        @Override protected Object handle(Request request, Response response, Object in) { return null; }
    }

    static final class Unprovided extends CodecHandler<In, Word, Missing, Said> {
        @Override protected Said handle(Request request, Response response, Word word) { return null; }
    }

    static final class Missing implements Codec {
        @Override public <T> T read(Request request, Type type) { return null; }
        @Override public Object write(Response response, Object value) { return null; }
        @Override public ContentType contentType() { return ContentType.JSON; }
    }

    @Test
    void eachEndGoesThroughTheCodecItNames() throws Exception {
        Echo handler = new Echo();
        handler.bind(context());
        Response response = new Response(200, ContentType.NONE);

        Object body = handler.handle(request("hello"), response);

        assertEquals("said:hello", body);
        assertEquals("application/json", new String(response.getContentType(), StandardCharsets.UTF_8));
    }

    @Test
    void aHandlerThatReadsNothingNeverAsksForARequestCodec() throws Exception {
        FlashContext ctx = new FlashContext();
        ctx.provide(Out.class, new Out());
        ctx.complete();

        Answer handler = new Answer();
        handler.bind(ctx);

        assertEquals("said:nothing", handler.handle(request(""), new Response(200, ContentType.NONE)));
    }

    @Test
    void aTextOrBytesAnswerIsAlreadyABodyAndIsLeftAlone() throws Exception {
        Response response = new Response(200, ContentType.NONE);

        Object body = new Text().bound(context()).handle(request(""), response);

        assertEquals("plain", body);
    }

    @Test
    void aHandlerThatLeavesItsTypesOpenIsRefusedAtBoot() {
        FlashContext ctx = context();

        assertTrue(assertThrows(IllegalStateException.class, () -> new Open().bind(ctx))
                .getMessage().contains("leaves its type arguments open"));
    }

    @Test
    void aCodecNothingProvidesFailsTheBootNamingIt() {
        FlashContext ctx = context();

        assertTrue(assertThrows(IllegalStateException.class, () -> new Unprovided().bind(ctx))
                .getMessage().contains(Missing.class.getName()));
    }

    static final class Text extends CodecHandler<In, Void, Out, String> {
        @Override protected String handle(Request request, Response response, Void nothing) { return "plain"; }
        Text bound(FlashContext ctx) { bind(ctx); return this; }
    }

    private static FlashContext context() {
        FlashContext ctx = new FlashContext();
        ctx.provide(In.class, new In());
        ctx.provide(Out.class, new Out());
        ctx.complete();
        return ctx;
    }

    private static Request request(String body) {
        return new Request(new RequestLine(HttpMethod.POST,
                new FastPathViews.StringByteView("/words"), null,
                new FastPathViews.StringByteView("HTTP/1.1"), new Http1HeaderMap()),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
