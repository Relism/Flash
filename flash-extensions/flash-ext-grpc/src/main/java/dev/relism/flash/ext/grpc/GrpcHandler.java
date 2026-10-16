package dev.relism.flash.ext.grpc;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.ext.protobuf.Protobuf;
import dev.relism.flash.ext.serde.CodecDuplexHandler;
import dev.relism.flash.http.AnsweredFailure;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.fpr.core.ByteView;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;

/**
 * gRPC transport and protobuf binding shared by its four explicit cardinalities. The same route
 * answers gRPC over HTTP/2 and gRPC-Web over any HTTP version, chosen by the request's content type.
 */
@Slf4j
public abstract sealed class GrpcHandler<I, O>
        extends CodecDuplexHandler<Protobuf, I, Protobuf, O>
        permits GrpcUnary, GrpcServerStreaming, GrpcClientStreaming, GrpcBidiStreaming {
    private static final byte[] HTTP_2 = "HTTP/2".getBytes(StandardCharsets.US_ASCII);
    static final int MAX_MESSAGE = 4 * 1024 * 1024;

    /** How a call's messages travel: gRPC, gRPC-Web, or gRPC-Web in base64. */
    enum Wire {
        GRPC("application/grpc"), WEB("application/grpc-web+proto"), TEXT("application/grpc-web-text+proto");

        final byte[] type;

        Wire(String type) {
            this.type = type.getBytes(StandardCharsets.US_ASCII);
        }

        /** How the request arrived; null for a message format other than protobuf. */
        static Wire of(Request request) {
            String type = request.header("content-type");
            if (type == null) return null;
            int parameters = type.indexOf(';');
            return switch ((parameters < 0 ? type : type.substring(0, parameters)).strip().toLowerCase(Locale.ROOT)) {
                case "application/grpc", "application/grpc+proto" -> GRPC;
                case "application/grpc-web", "application/grpc-web+proto" -> WEB;
                case "application/grpc-web-text", "application/grpc-web-text+proto" -> TEXT;
                default -> null;
            };
        }

        /** How the response leaves: as the request came, or in base64 when the client asks for it. */
        Wire answer(Request request) {
            String accept = request.header("accept");
            return this == WEB && accept != null && accept.contains("application/grpc-web-text") ? TEXT : this;
        }
    }

    abstract void invoke(Request request, Call<I, O> call) throws Exception;

    @Override
    protected final void prepare(Request request, Response response) {
        Wire wire = Wire.of(request);
        if (wire == null) throw new HttpException(415, "gRPC carries application/grpc or application/grpc-web, in protobuf");
        if (wire == Wire.GRPC && !http2(request.requestLine().getProtocol())) throw new HttpException(505, "gRPC runs over HTTP/2");
        response.type(wire.answer(request).type);
    }

    /** A failure ends the call with its status in the trailers, and still fails the work for the scopes around it. */
    @Override
    protected final void handle(Request request, Response response, Duplex duplex) {
        Wire wire = Wire.of(request);
        Call<I, O> call = new Call<>(this, duplex, response, wire, wire.answer(request));
        try {
            if (call.web && (this instanceof GrpcClientStreaming || this instanceof GrpcBidiStreaming))
                throw new GrpcException(Status.UNIMPLEMENTED, "gRPC-Web carries unary and server-streaming calls");
            invoke(request, call);
            call.close(null);
        } catch (Throwable failure) {
            try {
                call.close(failure);
            } catch (IOException gone) {
                failure.addSuppressed(gone);
            }
            throw new AnsweredFailure(failure);
        }
    }

    private static boolean http2(ByteView protocol) {
        if (protocol == null || protocol.length() != HTTP_2.length) return false;
        for (int i = 0; i < HTTP_2.length; i++) if (protocol.byteAt(i) != HTTP_2[i]) return false;
        return true;
    }

    static final class Call<I, O> extends GrpcInput<I> {
        private final GrpcHandler<I, O> handler;
        private final Duplex source;
        private final Duplex sink;
        private final Response response;
        final boolean web;
        private final byte[] prefix = new byte[5];
        private final ReentrantLock lock = new ReentrantLock();
        private final ByteWriter out = new ByteWriter(256); // frames not yet written
        private byte[] gathered = new byte[0];
        private boolean closed;

        Call(GrpcHandler<I, O> handler, Duplex duplex, Response response, Wire request, Wire answer) {
            this.handler = handler;
            Duplex text = answer == Wire.TEXT ? new GrpcWebText(duplex) : null;
            this.source = request == Wire.TEXT ? text : duplex;
            this.sink = text != null ? text : duplex;
            this.response = response;
            this.web = answer != Wire.GRPC;
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
                if (length == 0 || source.fill() >= length) {
                    byte[] bytes = length == 0 ? prefix : source.array();
                    int offset = length == 0 ? 0 : source.offset();
                    I value = handler.decode(bytes, offset, length);
                    if (length != 0) source.advance(length);
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
                append(value);
                drain();
                sink.flush();
            } finally {
                lock.unlock();
            }
        }

        /** The one reply of a unary or client-streaming call: it leaves with the trailers, in one write. */
        void reply(O value) throws IOException {
            lock.lock();
            try {
                append(value);
            } finally {
                lock.unlock();
            }
        }

        private void append(O value) throws IOException {
            if (closed) throw new IOException("the call has ended");
            int start = out.reserve(5);
            try {
                handler.encode(value, out);
            } catch (IOException | RuntimeException failure) {
                out.reset();
                throw failure;
            } catch (Exception failure) {
                out.reset();
                throw new IOException(failure);
            }
            frame(start, 0);
        }

        private void frame(int start, int flags) {
            byte[] bytes = out.array();
            int length = out.length() - start - 5;
            bytes[start] = (byte) flags;
            bytes[start + 1] = (byte) (length >>> 24);
            bytes[start + 2] = (byte) (length >>> 16);
            bytes[start + 3] = (byte) (length >>> 8);
            bytes[start + 4] = (byte) length;
        }

        private void drain() throws IOException {
            int length = out.length();
            if (length == 0) return;
            out.reset(); // first, so a failed write is not repeated by close
            sink.write(out.array(), 0, length);
        }

        /**
         * Ends the call with its status: in the HTTP trailers for gRPC; for gRPC-Web in a last frame
         * flagged {@code 0x80}, or in the headers when no message was sent (trailers-only).
         */
        void close(Throwable cause) throws IOException {
            lock.lock();
            try {
                if (closed) return;
                closed = true;
                Status status = cause == null ? Status.OK : status(cause);
                String message = cause == null ? null : message(cause);
                if (status == Status.INTERNAL && message == null) {
                    if (cause instanceof IOException || cause instanceof UncheckedIOException)
                        log.debug("gRPC call ended by its transport", cause);
                    else log.error("gRPC handler failed", cause);
                }
                String code = Integer.toString(status.ordinal());
                String detail = message == null ? null : percentEncoded(message);
                if (!web) {
                    drain();
                    response.trailer("grpc-status", code);
                    if (detail != null) response.trailer("grpc-message", detail);
                } else if (out.length() == 0 && !response.isCommitted()) {
                    response.header("grpc-status", code);
                    if (detail != null) response.header("grpc-message", detail);
                } else {
                    int start = out.reserve(5);
                    out.writeAscii("grpc-status: " + code + "\r\n");
                    if (detail != null) out.writeAscii("grpc-message: " + detail + "\r\n");
                    frame(start, 0x80);
                    drain();
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
                    case 401 -> Status.UNAUTHENTICATED;
                    case 403 -> Status.PERMISSION_DENIED;
                    case 404 -> Status.NOT_FOUND;
                    case 409 -> Status.ABORTED;
                    case 412 -> Status.FAILED_PRECONDITION;
                    case 429 -> Status.RESOURCE_EXHAUSTED;
                    case 501 -> Status.UNIMPLEMENTED;
                    case 503 -> Status.UNAVAILABLE;
                    case 504 -> Status.DEADLINE_EXCEEDED;
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
                int available = source.fill();
                if (available < 0) {
                    if (endAllowed && copied == 0) return false;
                    throw new EOFException("gRPC request ended mid-message");
                }
                int count = Math.min(available, length - copied);
                System.arraycopy(source.array(), source.offset(), into, offset + copied, count);
                source.advance(count);
                copied += count;
            }
            return true;
        }
    }
}
