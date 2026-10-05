package dev.relism.flash.ext.protobuf;

import com.google.protobuf.StringValue;
import dev.relism.flash.App;
import dev.relism.flash.ext.validation.ValidationException;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.POST;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The whole trip over a socket: bytes in, bytes out, and what each failure answers. */
class ProtobufHttpTest {

    @POST("/echo")
    public static final class Echo extends ProtobufHandler<StringValue, StringValue> {
        @Override protected StringValue handle(Request request, Response response, StringValue body) { return body; }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new ProtobufModule())
            .add(ProtobufValidation.class, value -> {
                if (value instanceof StringValue text && text.getValue().equals("bad"))
                    throw new ValidationException(List.of(new ValidationException.Violation("value", "must not be bad")));
            })
            .scan("dev.relism.flash.ext.protobuf"));

    @Test
    void aMessageComesBackAsTheSameBytesWithItsMediaType() throws Exception {
        byte[] sent = StringValue.of("flash").toByteArray();

        HttpResponse<byte[]> reply = post(sent);

        assertEquals(200, reply.statusCode());
        assertArrayEquals(sent, reply.body());
        assertEquals("application/protobuf", reply.headers().firstValue("content-type").orElse(null));
    }

    @Test
    void malformedWireDataAnswers400() throws Exception {
        assertEquals(400, post(new byte[] {10, 5, 'x'}).statusCode());
    }

    @Test
    void aBrokenRuleAnswers422() throws Exception {
        assertEquals(422, post(StringValue.of("bad").toByteArray()).statusCode());
    }

    private static HttpResponse<byte[]> post(byte[] body) throws Exception {
        return app.client().send(HttpRequest.newBuilder(URI.create(app.baseUri() + "/echo"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
