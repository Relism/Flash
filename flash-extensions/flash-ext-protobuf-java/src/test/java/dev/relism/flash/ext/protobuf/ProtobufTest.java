package dev.relism.flash.ext.protobuf;

import com.google.protobuf.StringValue;
import dev.relism.flash.App;
import dev.relism.flash.bytes.FastPathViews;
import dev.relism.flash.ext.validation.Validation;
import dev.relism.flash.ext.validation.ValidationException;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Http1HeaderMap;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestLine;
import dev.relism.flash.http.Response;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtobufTest {

    private final Protobuf protobuf = new Protobuf();

    static final class Echo extends ProtobufHandler<StringValue, StringValue> {
        @Override protected StringValue handle(Request request, Response response, StringValue body) { return body; }

        Object invoke(Request request, Response response) throws Exception {
            return super.handle(request, response);
        }
    }

    @Test
    void readsFromTheRequestStreamAndWritesTheFinalArray() throws Exception {
        StringValue message = StringValue.of("flash");

        assertEquals(message, protobuf.decoder(StringValue.class).decode(request(message.toByteArray())));

        Response response = new Response(200, ContentType.TEXT_PLAIN);
        assertArrayEquals(message.toByteArray(), protobuf.write(response, message));
        assertEquals("application/protobuf", new String(response.getContentType(), StandardCharsets.US_ASCII));
    }

    @Test
    void theEncoderWritesIntoTheBufferEvenWhenItHasToGrow() throws Exception {
        StringValue message = StringValue.of("x".repeat(1000));
        dev.relism.flash.bytes.ByteWriter out = new dev.relism.flash.bytes.ByteWriter(16);
        out.writeByte((byte) 7);

        protobuf.encoder(StringValue.class).encode(message, out);

        assertEquals(7, out.array()[0]);
        assertEquals(message, StringValue.parseFrom(java.util.Arrays.copyOfRange(out.array(), 1, out.length())));
    }

    @Test
    void malformedWireDataIsABadRequest() {
        HttpException failure = assertThrows(HttpException.class,
                () -> protobuf.decoder(StringValue.class).decode(request(new byte[] {10, 5, 'x'})));

        assertEquals(400, failure.status());
    }

    @Test
    void aNonMessageTypeFailsWhileTheReaderIsPrepared() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> protobuf.decoder(String.class));

        assertTrue(failure.getMessage().contains("protobuf Message"), failure.getMessage());
    }

    @Test
    void aTypeWithoutAGeneratedDefaultInstanceFailsClearly() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> protobuf.decoder(com.google.protobuf.Message.class));

        assertTrue(failure.getMessage().contains("generated protobuf message"), failure.getMessage());
    }

    @RegisterExtension
    static FlashTest checked = FlashTest.of(() -> App.create()
            .install(new ProtobufModule())
            .install(Validation.module(value -> {
                throw new ValidationException(List.of(
                        new ValidationException.Violation("value", "must not be flash")));
            })));

    @Test
    void theInstalledValidationEngineChecksParsedMessages() {
        StringValue message = StringValue.of("flash");

        ValidationException failure = assertThrows(ValidationException.class,
                () -> checked.services().create(Echo.class)
                        .invoke(request(message.toByteArray()), new Response(200, ContentType.NONE)));

        assertEquals(422, failure.status());
    }

    private static Request request(byte[] body) {
        return new Request(new RequestLine(HttpMethod.POST,
                new FastPathViews.StringByteView("/messages"), null,
                new FastPathViews.StringByteView("HTTP/1.1"), new Http1HeaderMap()), body);
    }
}
