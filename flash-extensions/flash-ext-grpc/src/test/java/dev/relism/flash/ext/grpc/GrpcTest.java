package dev.relism.flash.ext.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.StringValue;
import com.google.protobuf.WrappersProto;
import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.ext.protobuf.ProtobufModule;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.routing.Middleware;
import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.flash.http2.hpack.HpackEncoder;
import dev.relism.fpr.core.ByteView;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The four call shapes over a real HTTP/2 connection, the status in the trailers, and the boot's check against the service. */
class GrpcTest {

    static final FileDescriptor ECHO = echoProto();

    public static final class Unary extends GrpcUnary<StringValue, StringValue> {
        @Override protected StringValue handle(Request request, StringValue in) {
            if (in.getValue().equals("missing")) throw new GrpcException(Status.NOT_FOUND, "no order é");
            if (in.getValue().equals("taken")) throw dev.relism.flash.http.HttpException.conflict("taken");
            return text("hello " + in.getValue());
        }
    }

    public static final class Upload extends GrpcClientStreaming<StringValue, StringValue> {
        @Override protected StringValue handle(Request request, GrpcInput<StringValue> in) throws Exception {
            StringBuilder all = new StringBuilder();
            for (StringValue part; (part = in.next()) != null;) all.append(part.getValue());
            return text(all.toString());
        }
    }

    public static final class Watch extends GrpcServerStreaming<StringValue, StringValue> {
        @Override protected void handle(Request request, StringValue in, GrpcOutput<StringValue> out) throws Exception {
            for (int i = 0; i < 3; i++) out.send(text(in.getValue() + ":" + i));
        }
    }

    public static final class Talk extends GrpcBidiStreaming<StringValue, StringValue> {
        @Override protected void handle(Request request, GrpcInput<StringValue> in,
                                        GrpcOutput<StringValue> out) throws Exception {
            for (StringValue message; (message = in.next()) != null;)
                out.send(text(message.getValue().toUpperCase()));
        }
    }

    /** Writes until the transport stops it, and says how it was stopped. */
    public static final class Flood extends GrpcServerStreaming<StringValue, StringValue> {
        static final java.util.concurrent.CompletableFuture<Throwable> ENDED = new java.util.concurrent.CompletableFuture<>();

        @Override protected void handle(Request request, StringValue in, GrpcOutput<StringValue> out) throws Exception {
            try {
                for (int i = 0; i < 1_000_000; i++) out.send(text("x".repeat(1024)));
                ENDED.complete(null);
            } catch (Throwable stopped) {
                ENDED.complete(stopped);
                throw stopped;
            }
        }
    }

    private Server server;

    @AfterEach
    void stop() {
        if (server != null) server.stop().join();
    }

    private int start(App app) {
        server = Server.start(app.install(new ProtobufModule(), new GrpcModule(ECHO)),
                ServerConfig.builder().host("127.0.0.1").port(0).http2CleartextEnabled(true).build());
        return server.port();
    }

    @Test
    void everyShapeAnswersWithItsMessagesAndAnOkStatus() throws Exception {
        Middleware after = (exchange, next) -> {
            next.handle(exchange);
            exchange.response().header("x-after", "middleware");
        };
        int port = start(App.create()
                .route(HttpMethod.POST, "/flash.test.Echo/Unary", Unary.class, after)
                .route(HttpMethod.POST, "/flash.test.Echo/Upload", Upload.class)
                .route(HttpMethod.POST, "/flash.test.Echo/Watch", Watch.class)
                .route(HttpMethod.POST, "/flash.test.Echo/Talk", Talk.class));

        try (H2c client = new H2c(port)) {
            Call unary = client.call(1, "/flash.test.Echo/Unary", text("grpc"));
            assertEquals("application/grpc", unary.headers.get("content-type"));
            assertEquals("middleware", unary.headers.get("x-after"));
            assertEquals(List.of("hello grpc"), unary.messages);
            assertEquals("0", unary.trailers.get("grpc-status"));

            assertEquals(List.of("abc"), client.call(3, "/flash.test.Echo/Upload", text("a"), text("b"), text("c")).messages);
            assertEquals(List.of("w:0", "w:1", "w:2"), client.call(5, "/flash.test.Echo/Watch", text("w")).messages);
            Call talk = client.call(7, "/flash.test.Echo/Talk", text("x"), text("y"));
            assertEquals(List.of("X", "Y"), talk.messages);
            assertEquals("0", talk.trailers.get("grpc-status"));
        }
    }

    @Test
    void interoperatesWithTheOfficialGrpcJavaClient() throws Exception {
        int port = start(App.create().route(HttpMethod.POST, "/flash.test.Echo/Unary", Unary.class));
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
        try {
            MethodDescriptor<StringValue, StringValue> method =
                    MethodDescriptor.<StringValue, StringValue>newBuilder()
                            .setType(MethodDescriptor.MethodType.UNARY)
                            .setFullMethodName("flash.test.Echo/Unary")
                            .setRequestMarshaller(ProtoUtils.marshaller(StringValue.getDefaultInstance()))
                            .setResponseMarshaller(ProtoUtils.marshaller(StringValue.getDefaultInstance()))
                            .build();

            assertEquals("hello official", ClientCalls.blockingUnaryCall(
                    channel, method, CallOptions.DEFAULT, text("official")).getValue());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void anAppErrorIsItsStatusInTheTrailersAndAMalformedMessageIsInvalidArgument() throws Exception {
        List<Throwable> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        Middleware scope = (exchange, next) -> {
            next.handle(exchange);
            exchange.around(deferred -> {
                try {
                    deferred.call();
                } catch (Exception failed) {
                    seen.add(failed.getCause());
                    throw failed;
                }
            });
        };
        int port = start(App.create().route(HttpMethod.POST, "/flash.test.Echo/Unary", Unary.class, scope));

        try (H2c client = new H2c(port)) {
            Call missing = client.call(1, "/flash.test.Echo/Unary", text("missing"));
            assertEquals(List.of(), missing.messages);
            assertEquals("5", missing.trailers.get("grpc-status"));
            assertEquals("no order %C3%A9", missing.trailers.get("grpc-message"));
            // a scope around the handler sees it fail, so a transaction there rolls back
            assertTrue(seen.get(0) instanceof GrpcException);

            Call malformed = client.raw(3, "/flash.test.Echo/Unary", new byte[] {0, 0, 0, 0, 2, (byte) 0xFF, (byte) 0xFF});
            assertEquals("3", malformed.trailers.get("grpc-status"));

            Call conflict = client.call(5, "/flash.test.Echo/Unary", text("taken"));
            assertEquals(String.valueOf(Status.ABORTED.ordinal()), conflict.trailers.get("grpc-status"));
        }
    }

    @Test
    void aStreamTheClientResetsEndsItsHandlerAsATransportFailureAndTheConnectionGoesOn() throws Exception {
        int port = start(App.create()
                .route(HttpMethod.POST, "/flash.test.Echo/Watch", Flood.class)
                .route(HttpMethod.POST, "/flash.test.Echo/Unary", Unary.class));

        try (H2c client = new H2c(port)) {
            client.open(1, "/flash.test.Echo/Watch", text("go"));
            client.awaitData(1);
            client.reset(1);
            assertTrue(Flood.ENDED.get(5, TimeUnit.SECONDS) instanceof IOException);
            assertEquals(List.of("hello again"), client.call(3, "/flash.test.Echo/Unary", text("again")).messages);
        }
    }

    @Test
    void theBootChecksPathShapeAndTypesAgainstTheService() {
        String wrongShape = assertThrows(IllegalStateException.class, () -> start(App.create()
                .route(HttpMethod.POST, "/flash.test.Echo/Watch", Unary.class))).getMessage();
        assertTrue(wrongShape.contains("server streaming") && wrongShape.contains("matching Grpc* handler"), wrongShape);

        String typo = assertThrows(IllegalStateException.class, () -> start(App.create()
                .route(HttpMethod.POST, "/flash.test.Echo/Unray", Unary.class))).getMessage();
        assertTrue(typo.contains("no registered service"), typo);
    }

    @Test
    void grpcMessageIsPercentEncodedUtf8() {
        assertEquals("a%25b %0A%C3%A9", GrpcHandler.Call.percentEncoded("a%b \né"));
    }

    static StringValue text(String value) {
        return StringValue.of(value);
    }

    private static FileDescriptor echoProto() {
        ServiceDescriptorProto.Builder service = ServiceDescriptorProto.newBuilder().setName("Echo");
        String[][] methods = {{"Unary", "", ""}, {"Upload", "c", ""}, {"Watch", "", "s"}, {"Talk", "c", "s"}};
        for (String[] m : methods) {
            service.addMethod(MethodDescriptorProto.newBuilder().setName(m[0])
                    .setInputType(".google.protobuf.StringValue").setOutputType(".google.protobuf.StringValue")
                    .setClientStreaming(!m[1].isEmpty()).setServerStreaming(!m[2].isEmpty()));
        }
        FileDescriptorProto file = FileDescriptorProto.newBuilder().setName("echo.proto").setPackage("flash.test")
                .setSyntax("proto3").addDependency("google/protobuf/wrappers.proto").addService(service).build();
        try {
            return FileDescriptor.buildFrom(file, new FileDescriptor[] {WrappersProto.getDescriptor()});
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    record Call(Map<String, String> headers, List<String> messages, Map<String, String> trailers) {}

    /** A cleartext HTTP/2 client that knows exactly enough to make gRPC calls, one at a time. */
    static final class H2c implements Closeable {
        private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final HpackDecoder decoder = new HpackDecoder();

        H2c(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5_000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            out.write(PREFACE);
            frame(4, 0, 0, new byte[0]); // SETTINGS
            // room on the connection beyond one stream's window: a stream that is never read cannot starve the next
            frame(8, 0, 0, ByteBuffer.allocate(4).putInt(1 << 20).array()); // WINDOW_UPDATE
        }

        Call call(int stream, String path, StringValue... messages) throws IOException {
            return raw(stream, path, framed(messages));
        }

        /** Sends a call without reading its answer. */
        void open(int stream, String path, StringValue... messages) throws IOException {
            send(stream, path, framed(messages));
        }

        /** Reads until the stream's first DATA frame: its handler is running and writing. */
        void awaitData(int stream) throws IOException {
            while (true) {
                byte[] header = in.readNBytes(9);
                if (header.length < 9) throw new EOFException("connection closed");
                int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
                in.readNBytes(length);
                if (header[3] == 4 && (header[4] & 0x1) == 0) frame(4, 0x1, 0, new byte[0]); // ack SETTINGS
                if (header[3] == 0 && (ByteBuffer.wrap(header, 5, 4).getInt() & 0x7FFFFFFF) == stream) return;
            }
        }

        void reset(int stream) throws IOException {
            frame(3, 0, stream, new byte[] {0, 0, 0, 8}); // RST_STREAM, CANCEL
        }

        private static byte[] framed(StringValue... messages) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (StringValue message : messages) {
                byte[] bytes = message.toByteArray();
                body.write(0);
                body.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
                body.writeBytes(bytes);
            }
            return body.toByteArray();
        }

        Call raw(int stream, String path, byte[] body) throws IOException {
            send(stream, path, body);
            return read(stream);
        }

        private void send(int stream, String path, byte[] body) throws IOException {
            ByteWriter block = new ByteWriter(128);
            HpackEncoder.writeLiteralWithNameIndex(block, 2, ascii("POST"), false);
            HpackEncoder.writeLiteralWithNameIndex(block, 6, ascii("http"), false);
            HpackEncoder.writeLiteralWithNameIndex(block, 4, ascii(path), false);
            HpackEncoder.writeLiteralWithNameIndex(block, 1, ascii("localhost"), false);
            HpackEncoder.writeLiteralWithNameIndex(block, 31, ascii("application/grpc"), false);
            frame(1, 0x4, stream, java.util.Arrays.copyOf(block.array(), block.length())); // HEADERS, END_HEADERS
            frame(0, 0x1, stream, body); // DATA, END_STREAM
        }

        private Call read(int stream) throws IOException {
            List<Map<String, String>> blocks = new ArrayList<>();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            while (true) {
                byte[] header = in.readNBytes(9);
                if (header.length < 9) throw new EOFException("connection closed");
                int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
                int type = header[3], flags = header[4];
                int id = ByteBuffer.wrap(header, 5, 4).getInt() & 0x7FFFFFFF;
                byte[] payload = in.readNBytes(length);
                if (type == 4 && (flags & 0x1) == 0) frame(4, 0x1, 0, new byte[0]); // ack the server's SETTINGS
                if (id != stream) continue;
                if (type == 3) throw new IOException("stream reset");
                if (type == 0) data.writeBytes(payload);
                if (type == 1) {
                    Map<String, String> fields = new HashMap<>();
                    decoder.decode(payload, 0, payload.length, (name, value, neverIndexed) -> fields.put(text(name), text(value)));
                    blocks.add(fields);
                }
                if ((flags & 0x1) != 0) break;
            }
            List<String> messages = new ArrayList<>();
            ByteBuffer bytes = ByteBuffer.wrap(data.toByteArray());
            while (bytes.remaining() >= 5) {
                bytes.get();
                byte[] message = new byte[bytes.getInt()];
                bytes.get(message);
                messages.add(StringValue.parseFrom(message).getValue());
            }
            return new Call(blocks.get(0), messages, blocks.get(blocks.size() - 1));
        }

        private void frame(int type, int flags, int stream, byte[] payload) throws IOException {
            out.write(ByteBuffer.allocate(9).put((byte) (payload.length >> 16)).put((byte) (payload.length >> 8))
                    .put((byte) payload.length).put((byte) type).put((byte) flags).putInt(stream).array());
            out.write(payload);
            out.flush();
        }

        private static String text(ByteView view) {
            byte[] bytes = new byte[view.length()];
            for (int i = 0; i < bytes.length; i++) bytes[i] = view.byteAt(i);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private static byte[] ascii(String s) {
            return s.getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
