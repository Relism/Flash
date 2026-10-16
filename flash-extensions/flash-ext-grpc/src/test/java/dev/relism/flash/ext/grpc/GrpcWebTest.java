package dev.relism.flash.ext.grpc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.StringValue;
import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.ext.grpc.GrpcTest.Call;
import dev.relism.flash.ext.grpc.GrpcTest.H2c;
import dev.relism.flash.ext.limiter.Limit;
import dev.relism.flash.ext.limiter.LimiterModule;
import dev.relism.flash.ext.micrometer.MicrometerModule;
import dev.relism.flash.ext.protobuf.ProtobufModule;
import dev.relism.flash.ext.protobuf.ProtobufValidation;
import dev.relism.flash.ext.security.Authenticated;
import dev.relism.flash.ext.security.AuthenticationMechanism;
import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.ext.security.SecurityModule;
import dev.relism.flash.ext.validation.ValidationException;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** gRPC-Web on the same routes: binary and base64 framing, the status in the body or the headers, CORS. */
class GrpcWebTest {

    @Authenticated
    public static final class Secured extends GrpcUnary<StringValue, StringValue> {
        @Override protected StringValue handle(Request request, StringValue in) {
            return StringValue.of("secured " + in.getValue());
        }
    }

    @Limit(requests = 1, window = 60)
    public static final class Limited extends GrpcUnary<StringValue, StringValue> {
        @Override protected StringValue handle(Request request, StringValue in) {
            return StringValue.of("limited");
        }
    }

    private Server server;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        client.close();
        if (server != null) server.stop().join();
    }

    private int start(App app) {
        return serve(app.route(HttpMethod.POST, "/flash.test.Echo/Unary", GrpcTest.Unary.class)
                .route(HttpMethod.POST, "/flash.test.Echo/Upload", GrpcTest.Upload.class)
                .route(HttpMethod.POST, "/flash.test.Echo/Watch", GrpcTest.Watch.class)
                .route(HttpMethod.POST, "/flash.test.Echo/Talk", GrpcTest.Talk.class));
    }

    private int serve(App app) {
        server = Server.start(app.install(new ProtobufModule(), new GrpcModule(GrpcTest.ECHO)),
                ServerConfig.builder().host("127.0.0.1").port(0).http2CleartextEnabled(true).build());
        return server.port();
    }

    private HttpResponse<byte[]> post(String path, String type, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .version(HttpClient.Version.HTTP_1_1).header("content-type", type)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) request.headers(headers);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Call call(HttpResponse<byte[]> response, boolean text) throws IOException {
        Map<String, String> headers = new HashMap<>();
        response.headers().map().forEach((name, values) -> headers.put(name, values.get(0)));
        byte[] body = text ? Base64.getDecoder().decode(chunksOf(response.body())) : response.body();
        return Call.of(headers, body, headers);
    }

    /** Decodes padded chunks one quantum at a time, as a gRPC-Web client must. */
    private static byte[] chunksOf(byte[] base64) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (int i = 0; i < base64.length; i += 4)
            joined.writeBytes(Base64.getDecoder().decode(new String(base64, i, 4, StandardCharsets.US_ASCII)));
        return Base64.getEncoder().encode(joined.toByteArray());
    }

    @Test
    void unaryAndServerStreamingAnswerOverHttp1WithTheStatusInALastFrame() throws Exception {
        start(App.create());

        HttpResponse<byte[]> unary = post("/flash.test.Echo/Unary", "application/grpc-web+proto", H2c.framed(GrpcTest.text("web")));
        assertEquals(200, unary.statusCode());
        assertEquals("application/grpc-web+proto", unary.headers().firstValue("content-type").orElseThrow());
        Call answered = call(unary, false);
        assertEquals(List.of("hello web"), answered.messages());
        assertEquals("0", answered.trailers().get("grpc-status"));

        byte[] body = unary.body();
        int trailer = 5 + StringValue.of("hello web").getSerializedSize();
        assertEquals((byte) 0x80, body[trailer]);
        assertEquals("grpc-status: 0\r\n", new String(body, trailer + 5, body.length - trailer - 5, StandardCharsets.US_ASCII));

        Call watch = call(post("/flash.test.Echo/Watch", "application/grpc-web", H2c.framed(GrpcTest.text("w"))), false);
        assertEquals(List.of("w:0", "w:1", "w:2"), watch.messages());
        assertEquals("0", watch.trailers().get("grpc-status"));
    }

    @Test
    void aFailureBeforeAnyMessageIsTrailersOnly() throws Exception {
        start(App.create());

        HttpResponse<byte[]> missing = post("/flash.test.Echo/Unary", "application/grpc-web+proto", H2c.framed(GrpcTest.text("missing")));
        assertEquals(200, missing.statusCode());
        assertEquals(0, missing.body().length);
        assertEquals("5", missing.headers().firstValue("grpc-status").orElseThrow());
        assertEquals("no order %C3%A9", missing.headers().firstValue("grpc-message").orElseThrow());
    }

    @Test
    void clientAndBidiStreamingAreUnimplementedAndOtherFormatsAre415() throws Exception {
        start(App.create());

        for (String path : List.of("/flash.test.Echo/Upload", "/flash.test.Echo/Talk")) {
            HttpResponse<byte[]> refused = post(path, "application/grpc-web+proto", H2c.framed(GrpcTest.text("x")));
            assertEquals(String.valueOf(Status.UNIMPLEMENTED.ordinal()), refused.headers().firstValue("grpc-status").orElseThrow());
        }
        assertEquals(415, post("/flash.test.Echo/Unary", "application/grpc-web+json", "{}".getBytes()).statusCode());
        assertEquals(415, post("/flash.test.Echo/Unary", "application/json", "{}".getBytes()).statusCode());
        // classic gRPC still needs HTTP/2
        assertEquals(505, post("/flash.test.Echo/Unary", "application/grpc", H2c.framed(GrpcTest.text("x"))).statusCode());
    }

    @Test
    void textModeIsBase64BothWaysAndAnAcceptAskingForItEncodesTheAnswer() throws Exception {
        start(App.create());

        byte[] request = Base64.getEncoder().encode(H2c.framed(GrpcTest.text("text")));
        HttpResponse<byte[]> text = post("/flash.test.Echo/Watch", "application/grpc-web-text", request,
                "accept", "application/grpc-web-text");
        assertEquals("application/grpc-web-text+proto", text.headers().firstValue("content-type").orElseThrow());
        Call watch = call(text, true);
        assertEquals(List.of("text:0", "text:1", "text:2"), watch.messages());
        assertEquals("0", watch.trailers().get("grpc-status"));

        HttpResponse<byte[]> binaryIn = post("/flash.test.Echo/Unary", "application/grpc-web+proto",
                H2c.framed(GrpcTest.text("b")), "accept", "application/grpc-web-text");
        assertEquals(List.of("hello b"), call(binaryIn, true).messages());

        HttpResponse<byte[]> garbage = post("/flash.test.Echo/Unary", "application/grpc-web-text", "!!!!".getBytes());
        assertEquals(String.valueOf(Status.INVALID_ARGUMENT.ordinal()), garbage.headers().firstValue("grpc-status").orElseThrow());
    }

    @Test
    void theSameRoutesAnswerGrpcWebOverHttp2() throws Exception {
        int port = start(App.create());

        try (H2c h2 = new H2c(port)) {
            h2.contentType = "application/grpc-web+proto";
            Call unary = h2.call(1, "/flash.test.Echo/Unary", GrpcTest.text("h2"));
            assertEquals("application/grpc-web+proto", unary.headers().get("content-type"));
            assertEquals(List.of("hello h2"), unary.messages());
            assertEquals("0", unary.trailers().get("grpc-status"));

            Call missing = h2.call(3, "/flash.test.Echo/Unary", GrpcTest.text("missing"));
            assertEquals("5", missing.headers().get("grpc-status"));
        }
    }

    @Test
    void thePresetAnswersThePreflightAndExposesTheStatusHeaders() throws Exception {
        start(App.create().use(GrpcWeb.cors(List.of("https://app.example"), "authorization")));

        HttpResponse<byte[]> preflight = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/flash.test.Echo/Unary"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("origin", "https://app.example").header("access-control-request-method", "POST")
                .header("access-control-request-headers", "content-type,x-grpc-web,authorization").build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(204, preflight.statusCode());
        assertEquals("POST", preflight.headers().firstValue("access-control-allow-methods").orElseThrow());
        assertEquals("content-type, grpc-timeout, x-grpc-web, x-user-agent, authorization",
                preflight.headers().firstValue("access-control-allow-headers").orElseThrow());

        HttpResponse<byte[]> call = post("/flash.test.Echo/Unary", "application/grpc-web+proto",
                H2c.framed(GrpcTest.text("cors")), "origin", "https://app.example");
        assertEquals("https://app.example", call.headers().firstValue("access-control-allow-origin").orElseThrow());
        assertEquals("grpc-status, grpc-message, grpc-status-details-bin",
                call.headers().firstValue("access-control-expose-headers").orElseThrow());
    }

    /** Interop 8.2: the credential travels in the headers; the preflight is answered before authentication. */
    @Test
    void securityAuthenticatesAWebCallAndLeavesThePreflightAlone() throws Exception {
        AuthenticationMechanism token = request -> "Bearer good".equals(request.header("authorization")) ? () -> "alice" : null;
        serve(App.create().use(GrpcWeb.cors(List.of("https://app.example"), "authorization"))
                .install(new SecurityModule()).add(AuthenticationMechanism.class, token)
                .route(HttpMethod.POST, "/flash.test.Echo/Unary", Secured.class));

        HttpResponse<byte[]> preflight = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/flash.test.Echo/Unary"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("origin", "https://app.example").header("access-control-request-method", "POST").build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(204, preflight.statusCode());

        assertEquals(401, post("/flash.test.Echo/Unary", "application/grpc-web+proto", H2c.framed(GrpcTest.text("x"))).statusCode());
        Call allowed = call(post("/flash.test.Echo/Unary", "application/grpc-web+proto", H2c.framed(GrpcTest.text("x")),
                "authorization", "Bearer good"), false);
        assertEquals(List.of("secured x"), allowed.messages());
    }

    /** Interop 8.2: a protobuf validation engine's 422 becomes INVALID_ARGUMENT, as it does for gRPC. */
    @Test
    void aValidationViolationIsInvalidArgument() throws Exception {
        ProtobufValidation rejectsBad = value -> {
            if (value instanceof StringValue text && text.getValue().equals("bad"))
                throw new ValidationException(List.of(new ValidationException.Violation("value", "must not be bad")));
        };
        start(App.create().add(ProtobufValidation.class, rejectsBad));

        HttpResponse<byte[]> bad = post("/flash.test.Echo/Unary", "application/grpc-web+proto", H2c.framed(GrpcTest.text("bad")));
        assertEquals(String.valueOf(Status.INVALID_ARGUMENT.ordinal()), bad.headers().firstValue("grpc-status").orElseThrow());
        assertEquals(List.of("hello good"),
                call(post("/flash.test.Echo/Unary", "application/grpc-web+proto", H2c.framed(GrpcTest.text("good"))), false).messages());
    }

    /** Interop 8.2: the limiter answers before the call starts; Micrometer leaves gRPC out, like every duplex route. */
    @Test
    void theLimiterRefusesBeforeTheCallAndMetricsLeaveItOut() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        serve(App.create().install(new LimiterModule(), new MicrometerModule(registry))
                .route(HttpMethod.POST, "/flash.test.Echo/Unary", Limited.class)
                .get("/plain", (req, res) -> "plain"));

        assertEquals(List.of("limited"), call(post("/flash.test.Echo/Unary", "application/grpc-web+proto",
                H2c.framed(GrpcTest.text("x"))), false).messages());
        assertEquals(429, post("/flash.test.Echo/Unary", "application/grpc-web+proto", H2c.framed(GrpcTest.text("x"))).statusCode());
        client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/plain")).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(null, registry.find("http.server.requests").tag("uri", "/flash.test.Echo/Unary").timer());
        assertEquals(1, registry.find("http.server.requests").tag("uri", "/plain").timer().count());
    }

    @Test
    void base64SplitAtEveryPointAndPaddedMidStreamDecodesToTheSameBytes() throws Exception {
        byte[] first = "gRPC-Web text, first chunk".getBytes(StandardCharsets.US_ASCII);
        byte[] second = {0, (byte) 0xFF, 7};
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.writeBytes(Base64.getEncoder().encode(first)); // padded
        wire.writeBytes(Base64.getEncoder().encode(second));
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(first);
        expected.writeBytes(second);

        for (int step = 1; step <= wire.size(); step++) {
            GrpcWebText text = new GrpcWebText(new Chunks(wire.toByteArray(), step));
            ByteArrayOutputStream read = new ByteArrayOutputStream();
            for (int n; (n = text.fill()) > 0; text.advance(n)) read.write(text.array(), text.offset(), n);
            assertArrayEquals(expected.toByteArray(), read.toByteArray(), "split every " + step);
        }

        GrpcWebText cut = new GrpcWebText(new Chunks("QUJDR".getBytes(StandardCharsets.US_ASCII), 2));
        assertEquals(3, cut.fill());
        cut.advance(3);
        assertThrows(IOException.class, cut::fill);
    }

    @Test
    void eachWriteIsOnePaddedChunk() throws Exception {
        Chunks raw = new Chunks(new byte[0], 1);
        GrpcWebText text = new GrpcWebText(raw);
        text.write(new byte[] {'a'}, 0, 1);
        text.write(new byte[] {'x', 'b', 'c', 'y'}, 1, 2);
        assertEquals("YQ==YmM=", raw.written.toString(StandardCharsets.US_ASCII));
    }

    /** A duplex that hands out {@code bytes} {@code step} at a time and records what is written. */
    static final class Chunks extends Duplex {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final byte[] bytes;
        private final int step;
        private int position;
        private int limit;

        Chunks(byte[] bytes, int step) {
            this.bytes = bytes;
            this.step = step;
        }

        @Override public void write(byte[] b, int off, int len) { written.write(b, off, len); }
        @Override public void flush() {}

        @Override public int fill() {
            if (position < limit) return limit - position;
            if (limit == bytes.length) return -1;
            limit = Math.min(bytes.length, limit + step);
            return limit - position;
        }

        @Override public byte[] array() { return bytes; }
        @Override public int offset() { return position; }
        @Override public void advance(int n) { position += n; }
    }
}
