package dev.relism.flash.ext.serde;

import dev.relism.flash.App;
import dev.relism.flash.Services;
import dev.relism.flash.testing.FlashTest;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Http1HeaderMap;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestLine;
import dev.relism.flash.http.Response;
import dev.relism.flash.bytes.FastPathViews;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
        final List<Type> prepared = new ArrayList<>();
        @Override public void prepare(Type type) { prepared.add(type); }
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

    /** A format module's base: both codecs fixed, the carried types passed along. */
    static abstract class InOutHandler<I, O> extends CodecHandler<In, I, Out, O> {}

    /** Fixes the types one level further down, which is where they have to be read from. */
    static final class Relayed extends InOutHandler<Word, Said> {
        @Override protected Said handle(Request request, Response response, Word word) { return new Said(word.text()); }
    }

    static final class Unprovided extends CodecHandler<In, Word, Missing, Said> {
        @Override protected Said handle(Request request, Response response, Word word) { return null; }
    }

    static final class Missing implements Codec {
        @Override public <T> T read(Request request, Type type) { return null; }
        @Override public Object write(Response response, Object value) { return null; }
        @Override public ContentType contentType() { return ContentType.JSON; }
    }

    private static final Out OUT = new Out();

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create().add(In.class, new In()).add(Out.class, OUT));

    @RegisterExtension
    static FlashTest writeOnly = FlashTest.of(() -> App.create().add(Out.class, new Out()));

    private static Services services() {
        return app.services();
    }

    @Test
    void theTypesAreReadThroughAnIntermediateBase() throws Exception {
        Relayed handler = services().create(Relayed.class);
        Response response = new Response(200, ContentType.NONE);

        Object body = handler.handle(request("relayed"), response);

        assertEquals("said:relayed", body,
                "read through the request codec and written through the response codec the base named");
    }

    @Test
    void eachEndGoesThroughTheCodecItNames() throws Exception {
        Echo handler = services().create(Echo.class);
        Response response = new Response(200, ContentType.NONE);

        Object body = handler.handle(request("hello"), response);

        assertEquals("said:hello", body);
        assertEquals("application/json", new String(response.getContentType(), StandardCharsets.UTF_8));
    }

    @Test
    void aHandlerThatReadsNothingNeverAsksForARequestCodec() throws Exception {
        Answer handler = writeOnly.services().create(Answer.class);

        assertEquals("said:nothing", handler.handle(request(""), new Response(200, ContentType.NONE)));
    }

    @Test
    void aTextOrBytesAnswerIsAlreadyABodyAndIsLeftAlone() throws Exception {
        Response response = new Response(200, ContentType.NONE);

        Object body = services().create(Text.class).handle(request(""), response);

        assertEquals("plain", body);
    }

    @Test
    void eachCodecPreparesTheTypeItCarriesAndNothingItPassesThrough() {
        OUT.prepared.clear();

        services().create(Echo.class);
        services().create(Text.class);

        assertEquals(List.of(Said.class), OUT.prepared, "a String answer is already a body");
    }

    @Test
    void aHandlerThatLeavesItsTypesOpenIsRefusedAtBoot() {
        assertTrue(assertThrows(IllegalStateException.class, () -> services().create(Open.class))
                .getMessage().contains("leaves its type arguments open"));
    }

    @Test
    void aCodecTheAppDoesNotHaveFailsTheBootNamingIt() {
        assertTrue(assertThrows(IllegalStateException.class, () -> services().create(Unprovided.class))
                .getMessage().contains(Missing.class.getName()));
    }

    static final class Text extends CodecHandler<In, Void, Out, String> {
        @Override protected String handle(Request request, Response response, Void nothing) { return "plain"; }
    }

    private static Request request(String body) {
        return new Request(new RequestLine(HttpMethod.POST,
                new FastPathViews.StringByteView("/words"), null,
                new FastPathViews.StringByteView("HTTP/1.1"), new Http1HeaderMap()),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
