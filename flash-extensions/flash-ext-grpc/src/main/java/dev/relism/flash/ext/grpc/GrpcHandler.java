package dev.relism.flash.ext.grpc;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.ext.protobuf.Protobuf;
import dev.relism.flash.ext.serde.CodecDuplexHandler;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.fpr.core.ByteView;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;

/** gRPC transport and protobuf binding shared by its four explicit cardinalities. */
@Slf4j
public abstract sealed class GrpcHandler<I, O>
        extends CodecDuplexHandler<Protobuf, I, Protobuf, O>
        permits GrpcUnary, GrpcServerStreaming, GrpcClientStreaming, GrpcBidiStreaming {
    private static final byte[] CONTENT_TYPE = "application/grpc".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HTTP_2 = "HTTP/2".getBytes(StandardCharsets.US_ASCII);
    static final int MAX_MESSAGE = 4 * 1024 * 1024;

    abstract void invoke(Request request, Call<I, O> call) throws Exception;

    @Override
    protected final void prepare(Request request, Response response) {
        if (!http2(request.requestLine().getProtocol())) throw new HttpException(505, "gRPC runs over HTTP/2");
        String type = request.header("content-type");
        if (type == null || !(type.equals("application/grpc") || type.startsWith("application/grpc+proto")
                || type.startsWith("application/grpc;")))
            throw new HttpException(415, "gRPC carries application/grpc");
        response.type(CONTENT_TYPE);
    }

    @Override
    protected final void handle(Request request, Response response, Duplex duplex) {
        Call<I, O> call = new Call<>(this, duplex, response);
        try {
            invoke(request, call);
            call.close(null);
        } catch (Throwable failure) {
            call.close(failure);
        }
    }

    private static boolean http2(ByteView protocol) {
        if (protocol == null || protocol.length() != HTTP_2.length) return false;
        for (int i = 0; i < HTTP_2.length; i++) if (protocol.byteAt(i) != HTTP_2[i]) return false;
        return true;
    }

    static final class Call<I, O> extends GrpcInput<I> {
        private static final byte[] PREFIX = new byte[5];

        private final GrpcHandler<I, O> handler;
        private final Duplex duplex;
        private final Response response;
        private final byte[] prefix = new byte[5];
        private final ReentrantLock lock = new ReentrantLock();
        private final ByteWriter out = new ByteWriter(256);
        private byte[] gathered = new byte[0];
        private boolean closed;

        Call(GrpcHandler<I, O> handler, Duplex duplex, Response response) {
            this.handler = handler;
            this.duplex = duplex;
            this.response = response;
        }

        @Override
        public I next() throws IOException {
            if (!copy(prefix, 0, 5, true)) return null;
            if (prefix[0] != 0) throw new GrpcException(Status.UNIMPLEMENTED, "compressed messages are not supported");
            long size = ((prefix[1] & 0xFFL) << 24) | ((prefix[2] & 0xFFL) << 16)
                    | ((prefix[3] & 0xFFL) << 8) | (prefix[4] & 0xFFL);
            if (size > MAX_MESSAGE)
                throw new GrpcException(Status.RESOURCE_EXHAUSTED, "message exceeds " + MAX_MESSAGE + " bytes");
            int length = (int) size;
            try {
                if (length == 0 || duplex.fill() >= length) {
                    byte[] bytes = length == 0 ? prefix : duplex.array();
                    int offset = length == 0 ? 0 : duplex.offset();
                    I value = handler.decode(bytes, offset, length);
                    if (length != 0) duplex.advance(length);
                    return value;
                }
                if (gathered.length < length) gathered = new byte[Math.max(length, Math.max(256, gathered.length * 2))];
                copy(gathered, 0, length, false);
                return handler.decode(gathered, 0, length);
            } catch (IOException | RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException(failure);
            }
        }

        I required() throws IOException {
            I value = next();
            if (value == null) throw new GrpcException(Status.INVALID_ARGUMENT, "missing request message");
            return value;
        }

        void requireEnd() throws IOException {
            if (next() != null) throw new GrpcException(Status.INVALID_ARGUMENT, "too many request messages");
        }

        void send(O value) throws IOException {
            lock.lock();
            try {
                if (closed) throw new IOException("the call has ended");
                out.reset();
                out.writeBytes(PREFIX);
                try {
                    handler.encode(value, out);
                } catch (IOException | RuntimeException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IOException(failure);
                }
                byte[] bytes = out.array();
                int length = out.length() - PREFIX.length;
                bytes[0] = 0;
                bytes[1] = (byte) (length >>> 24);
                bytes[2] = (byte) (length >>> 16);
                bytes[3] = (byte) (length >>> 8);
                bytes[4] = (byte) length;
                duplex.write(bytes, 0, out.length());
                duplex.flush();
            } finally {
                lock.unlock();
            }
        }

        void close(Throwable cause) {
            lock.lock();
            try {
                if (closed) return;
                closed = true;
                Status status = cause == null ? Status.OK : status(cause);
                response.trailer("grpc-status", Integer.toString(status.ordinal()));
                String message = cause == null ? null : message(cause);
                if (message != null) response.trailer("grpc-message", percentEncoded(message));
                if (status == Status.INTERNAL && message == null) {
                    if (cause instanceof IOException || cause instanceof UncheckedIOException)
                        log.debug("gRPC call ended by its transport", cause);
                    else log.error("gRPC handler failed", cause);
                }
            } finally {
                lock.unlock();
            }
        }

        private static Status status(Throwable cause) {
            for (Throwable current = cause; current != null; current = current.getCause()) {
                if (current instanceof GrpcException grpc) return grpc.status();
                if (current instanceof HttpException refused) return switch (refused.status()) {
                    case 400, 422 -> Status.INVALID_ARGUMENT;
                    case 404 -> Status.NOT_FOUND;
                    default -> Status.INTERNAL;
                };
            }
            return Status.INTERNAL;
        }

        private static String message(Throwable cause) {
            for (Throwable current = cause; current != null; current = current.getCause())
                if (current instanceof GrpcException || current instanceof HttpException) return current.getMessage();
            return null;
        }

        static String percentEncoded(String message) {
            StringBuilder encoded = new StringBuilder(message.length());
            for (byte value : message.getBytes(StandardCharsets.UTF_8)) {
                if (value >= 0x20 && value <= 0x7E && value != '%') encoded.append((char) value);
                else encoded.append('%')
                        .append(Character.toUpperCase(Character.forDigit((value >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(value & 0xF, 16)));
            }
            return encoded.toString();
        }

        private boolean copy(byte[] into, int offset, int length, boolean endAllowed) throws IOException {
            int copied = 0;
            while (copied < length) {
                int available = duplex.fill();
                if (available < 0) {
                    if (endAllowed && copied == 0) return false;
                    throw new EOFException("gRPC request ended mid-message");
                }
                int count = Math.min(available, length - copied);
                System.arraycopy(duplex.array(), duplex.offset(), into, offset + copied, count);
                duplex.advance(count);
                copied += count;
            }
            return true;
        }
    }
}
