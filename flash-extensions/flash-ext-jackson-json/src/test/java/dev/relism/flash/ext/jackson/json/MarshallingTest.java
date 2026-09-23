package dev.relism.flash.ext.jackson.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.ext.jackson.Marshalling;
import dev.relism.flash.routing.Middleware;
import dev.relism.flash.models.SimpleHandler;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarshallingTest {

    private static final Request REQ = null;

    @Test
    void marshalling_marshalsPojo_toJsonBytes_and_setsJsonContentType() throws Exception {
        Middleware mw = Marshalling.of(new ObjectMapper(), ContentType.JSON);
        RequestHandler wrapped = wrap(mw, new UserDto("u1", "alice"));
        Response res = new Response(200, ContentType.TEXT_PLAIN);

        Object out = wrapped.handle(REQ, res);

        assertInstanceOf(byte[].class, out);
        assertEquals("application/json", new String(res.getContentType(), StandardCharsets.UTF_8));
        assertEquals("{\"id\":\"u1\",\"name\":\"alice\"}", new String((byte[]) out, StandardCharsets.UTF_8));
    }

    @Test
    void marshalling_passThrough_for_response_string_charSequence_bytes_and_null() throws Exception {
        Middleware mw = Marshalling.of(new ObjectMapper(), ContentType.JSON);

        Response payloadResponse = new Response(201, ContentType.TEXT_PLAIN).body("ok");
        RequestHandler wrappedResponse = wrap(mw, payloadResponse);
        Response res = new Response(200, ContentType.TEXT_PLAIN);
        assertSame(payloadResponse, wrappedResponse.handle(REQ, res));

        String s = "hello";
        assertSame(s, wrap(mw, s).handle(REQ, res));

        CharSequence cs = new StringBuilder("hello-cs");
        assertSame(cs, wrap(mw, cs).handle(REQ, res));

        byte[] bytes = new byte[]{1, 2, 3};
        assertSame(bytes, wrap(mw, bytes).handle(REQ, res));

        assertSame(null, wrap(mw, null).handle(REQ, res));
    }

    @Test
    void marshalling_wraps_serialization_errors_as_illegal_state() {
        Middleware mw = Marshalling.of(new ObjectMapper(), ContentType.JSON);
        RequestHandler wrapped = wrap(mw, new CyclicDto());
        Response res = new Response(200, ContentType.TEXT_PLAIN);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> wrapped.handle(REQ, res));
        assertEquals("application/json", new String(res.getContentType(), StandardCharsets.UTF_8));
        assertTrue(ex.getMessage().startsWith("Could not serialize"));
    }

    private static RequestHandler wrap(Middleware mw, Object fixedReturn) {
        RequestHandler next = new RequestHandler() {
            @Override
            public Object handle(Request request, Response response) {
                return fixedReturn;
            }
        };
        return new RequestHandler() {
            private final SimpleHandler.FunctionalHandler delegate = mw.wrap(next);

            @Override
            public Object handle(Request request, Response response) throws Exception {
                return delegate.handle(request, response);
            }
        };
    }

    private record UserDto(String id, String name) {}

    private static final class CyclicDto {
        CyclicDto self = this;
    }
}
