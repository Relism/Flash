package dev.relism.flash.http;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.bytes.FastPathViews;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedHandlerTest {

    record Payload(String text) {}

    /** An intermediate base that passes its own type arguments along, as a format module's does. */
    static abstract class TextHandler<I, O> extends TypedHandler<I, O> {
        @Override protected I read(Request request) {
            return parse(new String(request.body().bytes(), StandardCharsets.UTF_8));
        }
        @Override protected Object write(Response response, O answer) {
            return "wrote:" + answer;
        }
        protected abstract I parse(String text);
    }

    static final class Echo extends TextHandler<Payload, String> {
        @Override protected Payload parse(String text) { return new Payload(text); }
        @Override protected String handle(Request request, Response response, Payload body) { return body.text(); }
    }

    static final class Answers extends TypedHandler<Void, List<Payload>> {
        @Override protected Void read(Request request) { return null; }
        @Override protected Object write(Response response, List<Payload> answer) { return answer; }
        @Override protected List<Payload> handle(Request request, Response response, Void nothing) { return List.of(); }
    }

    @Test
    void bothTypesAreReadThroughTheWholeChain() {
        assertEquals(Payload.class, TypedHandler.typeArgument(Echo.class, TypedHandler.class, 0),
                "resolved past the intermediate base");
        assertEquals(String.class, TypedHandler.typeArgument(Echo.class, TypedHandler.class, 1));
        assertEquals(Void.class, TypedHandler.typeArgument(Answers.class, TypedHandler.class, 0));
        assertTrue(TypedHandler.typeArgument(Answers.class, TypedHandler.class, 1).getTypeName()
                .endsWith("List<dev.relism.flash.http.TypedHandlerTest$Payload>"), "generics are kept whole");
        assertNull(TypedHandler.typeArgument(Handler.class, TypedHandler.class, 0),
                "a handler that names no types declares none");
    }

    @Test
    void theRequestIsReadAndTheAnswerWrittenAroundTheHandler() throws Exception {
        Object answer = new Echo().handle(request("hello"), new Response(200, ContentType.NONE));

        assertEquals("wrote:hello", answer);
    }

    private static Request request(String body) {
        return new Request(new RequestLine(HttpMethod.POST,
                new FastPathViews.StringByteView("/echo"), null,
                new FastPathViews.StringByteView("HTTP/1.1"), new Http1HeaderMap()),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
