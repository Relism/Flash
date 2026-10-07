package dev.relism.flash.ext.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodecBindingTest {

    static class Probe implements Codec {
        final List<Type> decoded = new ArrayList<>();
        final List<Type> encoded = new ArrayList<>();

        @Override public Decoder decoder(Type type) {
            decoded.add(type);
            return (bytes, offset, length) -> null;
        }

        @Override public Encoder encoder(Type type) {
            encoded.add(type);
            return new Encoder() {
                @Override public void encode(Object value, ByteWriter output) {}
                @Override public ContentType type() { return ContentType.BINARY; }
            };
        }

        @Override public Object write(Response response, Object value) { return value; }
        @Override public ContentType contentType() { return ContentType.BINARY; }
    }

    static final class Missing extends Probe {}

    static abstract class RequestBase<T>
            extends CodecRequestHandler<Probe, List<T>, Probe, List<T>> {}

    static final class Strings extends RequestBase<String> {
        @Override protected List<String> handle(Request request, Response response, List<String> input) {
            return input;
        }
    }

    static abstract class DuplexBase<T>
            extends CodecDuplexHandler<Probe, List<T>, Probe, List<T>> {}

    static final class StringDuplex extends DuplexBase<String> {
        @Override protected void handle(Request request, Response response, Duplex duplex) {}
    }

    static final class Unprovided
            extends CodecRequestHandler<Missing, String, Probe, String> {
        @Override protected String handle(Request request, Response response, String input) { return input; }
    }

    @SuppressWarnings("rawtypes")
    static final class Open extends CodecRequestHandler {
        @Override protected Object handle(Request request, Response response, Object input) { return input; }
    }

    @Test
    void resolvesNestedTypesThroughIntermediateRequestAndDuplexBases() {
        Probe probe = new Probe();
        Server server = start(App.create().add(Probe.class, probe)
                .route(HttpMethod.POST, "/request", Strings.class)
                .route(HttpMethod.CONNECT, "duplex", StringDuplex.class));
        try {
            assertEquals(List.of(
                    "java.util.List<java.lang.String>",
                    "java.util.List<java.lang.String>"),
                    probe.decoded.stream().map(Type::getTypeName).toList());
            assertEquals(List.of(
                    "java.util.List<java.lang.String>",
                    "java.util.List<java.lang.String>"),
                    probe.encoded.stream().map(Type::getTypeName).toList());
        } finally {
            server.stop().join();
        }
    }

    @Test
    void missingCodecAndOpenTypesFailAtBoot() {
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> start(App.create().add(Probe.class, new Probe())
                        .route(HttpMethod.POST, "/", Unprovided.class)));
        assertTrue(missing.getMessage().contains(Missing.class.getName()), missing.getMessage());

        IllegalStateException open = assertThrows(IllegalStateException.class,
                () -> start(App.create().add(Probe.class, new Probe())
                        .route(HttpMethod.POST, "/", Open.class)));
        assertTrue(open.getMessage().contains("open"), open.getMessage());
    }

    private static Server start(App app) {
        return Server.start(app, ServerConfig.builder().host("127.0.0.1").port(0).build());
    }
}
