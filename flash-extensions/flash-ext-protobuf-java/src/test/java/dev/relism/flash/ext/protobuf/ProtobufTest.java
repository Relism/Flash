package dev.relism.flash.ext.protobuf;

import com.google.protobuf.StringValue;
import dev.relism.flash.App;
import dev.relism.flash.bytes.FastPathViews;
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

        assertEquals(message, protobuf.read(request(message.toByteArray()), StringValue.class));

        Response response = new Response(200, ContentType.TEXT_PLAIN);
        assertArrayEquals(message.toByteArray(), protobuf.write(response, message));
        assertEquals("application/protobuf", new String(response.getContentType(), StandardCharsets.US_ASCII));
    }

    @Test
    void malformedWireDataIsABadRequest() {
        HttpException failure = assertThrows(HttpException.class,
                () -> protobuf.read(request(new byte[] {10, 5, 'x'}), StringValue.class));

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
            .add(ProtobufValidation.class, value -> {
                throw new ValidationException(List.of(
                        new ValidationException.Violation("value", "must not be flash")));
            }));

    @Test
    void theInstalledValidationEngineChecksParsedMessages() {
        StringValue message = StringValue.of("flash");

        ValidationException failure = assertThrows(ValidationException.class,
                () -> checked.services().create(Echo.class)
                        .invoke(request(message.toByteArray()), new Response(200, ContentType.NONE)));

        assertEquals(422, failure.status());
    }

    static final class Engine implements ProtobufValidation {
        @Override public void verify(Object value) {}
    }

    static final class OtherEngine implements ProtobufValidation {
        @Override public void verify(Object value) {}
    }

    @Test
    void anEngineForProtobufAddedAsValidationFailsTheBootNamingIt() {
        FlashTest misplaced = FlashTest.of(() -> App.create().install(new ProtobufModule())
                .add(dev.relism.flash.ext.validation.Validation.class, new Engine()));

        assertTrue(failure(() -> misplaced.services()).contains("Engine"));
    }

    @Test
    void twoEnginesForProtobufFailTheBootNamingBoth() {
        FlashTest twice = FlashTest.of(() -> App.create().install(new ProtobufModule())
                .add(ProtobufValidation.class, new Engine()).add(ProtobufValidation.class, new OtherEngine()));

        String message = failure(() -> twice.services());
        assertTrue(message.contains("Engine") && message.contains("OtherEngine"), message);
    }

    private static String failure(Runnable boot) {
        StringBuilder said = new StringBuilder();
        for (Throwable cause = assertThrows(RuntimeException.class, boot::run); cause != null; cause = cause.getCause())
            said.append(cause.getMessage()).append(' ');
        return said.toString();
    }

    private static Request request(byte[] body) {
        return new Request(new RequestLine(HttpMethod.POST,
                new FastPathViews.StringByteView("/messages"), null,
                new FastPathViews.StringByteView("HTTP/1.1"), new Http1HeaderMap()), body);
    }
}
