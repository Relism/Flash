package dev.relism.flash.websocket;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.PreEncodedHeader;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.http1.Http1KeepAlive;
import dev.relism.fpr.core.ByteView;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;

/**
 * RFC 6455 over a {@link Duplex}: the handshake, an HTTP/1.1 upgrade or an HTTP/2 extended CONNECT
 * (RFC 8441), then whole messages. Fragments are reassembled, pings answered and the closing
 * handshake kept inside: a handler sees messages only. A text message goes out for an encoder that
 * writes text, a binary one otherwise.
 *
 * <p>A message that arrived in one frame inside the connection's read buffer is unmasked and handed
 * out where it lies. One split across frames or buffers is gathered into a buffer of the
 * connection's, up to {@code wsFrameBufferSize} bytes; a longer one closes with 1009. The channel
 * closes with 1000 when the handler returns, 1011 when it throws, 1007 for a message its codec
 * refuses and 1002 for a broken frame.
 */
@Slf4j
final class WebSocketFraming {

    private static final byte[] GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StandardCharsets.ISO_8859_1);
    private static final PreEncodedHeader UPGRADE = new PreEncodedHeader("Upgrade", "websocket");
    private static final PreEncodedHeader CONNECTION = new PreEncodedHeader("Connection", "Upgrade");

    private WebSocketFraming() {}

    static void prepare(Request request, Response response) {
        // An HTTP/2 extended CONNECT asked for this already, and is accepted with a plain 200.
        if (!"websocket".equals(request.connectProtocol())) {
            if (!isUpgrade(request))
                throw new HttpException(426, "a WebSocket endpoint: upgrade to it").header("Upgrade", "websocket");
            ByteView key = request.headerView("Sec-WebSocket-Key");
            if (key == null) throw HttpException.badRequest("missing Sec-WebSocket-Key");
            response.status(101).header(UPGRADE).header(CONNECTION).header("Sec-WebSocket-Accept", accept(key));
        }
        response.type(ContentType.NONE);
    }

    static Session open(Duplex duplex, int maxMessage) throws IOException {
        duplex.flush();
        return new Session(duplex, maxMessage);
    }

    /** {@code Upgrade: websocket} and {@code upgrade} among the {@code Connection} tokens (RFC 6455 §4.2.1). */
    private static boolean isUpgrade(Request request) {
        ByteView upgrade = request.headerView("Upgrade");
        return upgrade != null && Http1KeepAlive.tokenEqualsIgnoreCase(upgrade, 0, upgrade.length(), "websocket")
                && Http1KeepAlive.connectionContainsToken(request, "upgrade");
    }

    private static String accept(ByteView key) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        for (int i = 0, length = key.length(); i < length; i++) sha1.update(key.byteAt(i));
        sha1.update(GUID);
        return Base64.getEncoder().encodeToString(sha1.digest());
    }

    /** One connection's messages. Reading belongs to the handler's thread; sending is locked. */
    static final class Session {

        static final byte CONTINUATION = 0, TEXT = 1, BINARY = 2, CLOSE = 8, PING = 9, PONG = 10;
        private static final byte[] HEADROOM = new byte[10]; // the longest header a server writes

        private final Duplex duplex;
        private final int maxMessage;
        private final byte[] header = new byte[14];
        private final byte[] control = new byte[125];
        // ReentrantLock, not synchronized: a send blocked on the socket must not pin its carrier (Java 21).
        private final ReentrantLock writeLock = new ReentrantLock();
        private final ByteWriter out = new ByteWriter(256);
        private byte[] gathered = new byte[0];
        private byte[] array = control;
        private int offset;
        private int length;
        private int lent; // bytes of the message handed out in place, advanced over by the next call
        private byte opcode;
        private int closeCode = 1000;
        private String closeReason = "";
        private volatile boolean closed;
        private final CountDownLatch ended = new CountDownLatch(1);

        Session(Duplex duplex, int maxMessage) {
            this.duplex = duplex;
            this.maxMessage = maxMessage;
        }

        boolean next() throws IOException {
            if (lent != 0) {
                duplex.advance(lent);
                lent = 0;
            }
            byte message = 0; // the opcode of a fragmented message in progress, 0 for none
            int size = 0;
            int utf8 = Utf8.ACCEPT;
            while (true) {
                if (!copy(header, 0, 2, message == 0)) return false; // the peer left between messages
                int b0 = header[0], b1 = header[1];
                boolean fin = (b0 & 0x80) != 0;
                byte opcode = (byte) (b0 & 0x0F);
                // RSV1-3 mean something to a negotiated extension only, and none is negotiated.
                if ((b0 & 0x70) != 0) throw new WebSocketProtocolException(1002, "RSV bits must be zero");
                if ((b1 & 0x80) == 0) throw new WebSocketProtocolException(1002, "client frame must be masked");
                int bits = b1 & 0x7F;
                int extended = bits == 127 ? 8 : bits == 126 ? 2 : 0;
                copy(header, 0, extended + 4, false);
                long payload = bits;
                if (extended == 2) {
                    payload = ((header[0] & 0xFFL) << 8) | (header[1] & 0xFFL);
                } else if (extended == 8) {
                    if ((header[0] & 0x80) != 0) throw new WebSocketProtocolException(1002, "extended payload length MSB must be 0");
                    payload = 0;
                    for (int i = 0; i < 8; i++) payload = (payload << 8) | (header[i] & 0xFFL);
                }
                byte m0 = header[extended], m1 = header[extended + 1], m2 = header[extended + 2], m3 = header[extended + 3];

                if (opcode >= CLOSE) {
                    if (opcode > PONG) throw new WebSocketProtocolException(1002, "reserved opcode " + opcode);
                    if (!fin) throw new WebSocketProtocolException(1002, "control frame must not be fragmented");
                    if (payload > control.length) throw new WebSocketProtocolException(1002, "control frame payload exceeds 125 bytes");
                    int n = (int) payload;
                    copy(control, 0, n, false);
                    unmask(control, 0, n, m0, m1, m2, m3);
                    if (opcode == PING && !closed) send(PONG, control, 0, n);
                    else if (opcode == CLOSE) return closedByPeer(n);
                    continue; // a control frame may arrive between the fragments of a message (§5.4)
                }
                if (opcode > BINARY) throw new WebSocketProtocolException(1002, "reserved opcode " + opcode);
                if (opcode == CONTINUATION && message == 0) throw new WebSocketProtocolException(1002, "continuation frame without an initiated message");
                if (opcode != CONTINUATION && message != 0) throw new WebSocketProtocolException(1002, "new data frame while a fragmented message is in progress");
                if (size + payload > maxMessage) throw new WebSocketProtocolException(1009, "message exceeds " + maxMessage + " bytes");
                int n = (int) payload;
                byte kind = opcode == CONTINUATION ? message : opcode;

                if (fin && message == 0 && (n == 0 || duplex.fill() >= n)) {
                    // Whole and in the read buffer already: unmasked and handed out where it lies.
                    array = n == 0 ? control : duplex.array();
                    offset = n == 0 ? 0 : duplex.offset();
                    length = n;
                    this.opcode = kind;
                    lent = n;
                    unmask(array, offset, n, m0, m1, m2, m3);
                    if (kind == TEXT && Utf8.validate(Utf8.ACCEPT, array, offset, n) != Utf8.ACCEPT)
                        throw new WebSocketProtocolException(1007, "text message is not valid UTF-8");
                    return true;
                }
                // ponytail: a message split across frames or buffers is copied once, into the gathering buffer
                if (gathered.length < size + n) gathered = Arrays.copyOf(gathered, Math.min(maxMessage, Math.max(size + n, 2 * gathered.length)));
                copy(gathered, size, n, false);
                unmask(gathered, size, n, m0, m1, m2, m3);
                if (kind == TEXT) {
                    // RFC 6455 §8.1: judged as the bytes land, so a broken fragment fails at once.
                    utf8 = Utf8.validate(utf8, gathered, size, n);
                    if (utf8 == Utf8.REJECT) throw new WebSocketProtocolException(1007, "text message is not valid UTF-8");
                }
                size += n;
                message = kind;
                if (fin) {
                    if (utf8 != Utf8.ACCEPT) throw new WebSocketProtocolException(1007, "text message ends mid UTF-8 sequence");
                    array = gathered;
                    offset = 0;
                    length = size;
                    this.opcode = kind;
                    return true;
                }
            }
        }

        byte[] array() {
            return array;
        }

        int offset() {
            return offset;
        }

        int length() {
            return length;
        }

        byte opcode() {
            return opcode;
        }

        int closeCode() {
            return closeCode;
        }

        String closeReason() {
            return closeReason;
        }

        boolean isOpen() {
            return !closed;
        }

        void await() throws InterruptedException {
            ended.await();
        }

        void send(byte opcode, byte[] value, int offset, int length) throws IOException {
            writeLock.lock();
            try {
                if (closed) throw new IOException("the WebSocket is closed");
                out.reset();
                out.writeBytes(HEADROOM);
                out.writeBytes(value, offset, length);
                frame(opcode);
            } finally {
                writeLock.unlock();
            }
        }

        void close(Throwable cause) {
            int code = cause == null ? 1000 : codeFor(cause);
            if (code == 1011 && !(cause instanceof IOException || cause instanceof UncheckedIOException))
                log.error("WebSocket handler failed", cause);
            try {
                sendClose(code, "");
            } catch (IOException gone) {
                log.debug("WebSocket closed before its CLOSE frame left", gone);
            }
        }

        void close(int code, String reason) throws IOException {
            if (!sendable(code)) throw new IllegalArgumentException("reserved or undefined close code " + code);
            sendClose(code, reason);
        }

        private void sendClose(int code, String reason) throws IOException {
            byte[] words = reason.getBytes(StandardCharsets.UTF_8);
            if (words.length > 123) throw new IllegalArgumentException("close reason exceeds 123 bytes");
            writeLock.lock();
            try {
                if (closed) return;
                closed = true;
                closeCode = code;
                closeReason = reason;
                out.reset();
                out.writeBytes(HEADROOM);
                out.writeByte((byte) (code >> 8));
                out.writeByte((byte) code);
                out.writeBytes(words);
                frame(CLOSE);
            } finally {
                ended.countDown();
                writeLock.unlock();
            }
        }

        /** The close code a failure stands for: the protocol's own, 1007 for a refused message, 1011 for the rest. */
        private static int codeFor(Throwable cause) {
            for (Throwable t = cause; t != null; t = t.getCause()) {
                if (t instanceof WebSocketProtocolException broken) return broken.closeCode();
                if (t instanceof HttpException refused && (refused.status() == 400 || refused.status() == 422)) return 1007;
            }
            return 1011;
        }

        /**
         * Answers the peer's CLOSE with its own code (RFC 6455 §5.5.1), after checking the code
         * against §7.4.1 and the reason against UTF-8. An empty CLOSE is answered with 1000.
         */
        private boolean closedByPeer(int n) throws IOException {
            if (n == 1) throw new WebSocketProtocolException(1002, "close payload of one byte carries no status code");
            int code = 1000; // an empty CLOSE is answered with 1000
            String reason = "";
            if (n >= 2) {
                code = ((control[0] & 0xFF) << 8) | (control[1] & 0xFF);
                if (!sendable(code)) throw new WebSocketProtocolException(1002, "reserved or undefined close code " + code);
                if (Utf8.validate(Utf8.ACCEPT, control, 2, n - 2) != Utf8.ACCEPT)
                    throw new WebSocketProtocolException(1007, "close reason is not valid UTF-8");
                reason = new String(control, 2, n - 2, StandardCharsets.UTF_8);
            }
            closeCode = code;
            closeReason = reason;
            sendClose(code, reason);
            return false;
        }

        private static boolean sendable(int code) {
            return code >= 3000 && code <= 4999
                    || code >= 1000 && code <= 1014 && code != 1004 && code != 1005 && code != 1006;
        }

        /** Fills the header in front of the payload {@link #out} holds after its headroom, and sends both in one write. */
        private void frame(byte opcode) throws IOException {
            byte[] buf = out.array();
            int n = out.length() - HEADROOM.length;
            int at;
            if (n <= 125) {
                at = HEADROOM.length - 2;
                buf[at + 1] = (byte) n;
            } else if (n <= 0xFFFF) {
                at = HEADROOM.length - 4;
                buf[at + 1] = 126;
                buf[at + 2] = (byte) (n >> 8);
                buf[at + 3] = (byte) n;
            } else {
                at = 0;
                buf[1] = 127;
                for (int i = 0; i < 8; i++) buf[2 + i] = (byte) (i < 4 ? 0 : n >>> (8 * (7 - i)));
            }
            buf[at] = (byte) (0x80 | opcode);
            duplex.write(buf, at, out.length() - at);
            duplex.flush();
        }

        /** Copies {@code n} bytes off the duplex; false at a clean end before the first, when that is allowed. */
        private boolean copy(byte[] into, int at, int n, boolean endAllowed) throws IOException {
            int done = 0;
            while (done < n) {
                int available = duplex.fill();
                if (available < 0) {
                    if (endAllowed && done == 0) return false;
                    throw new EOFException("WebSocket stream closed mid-frame");
                }
                int take = Math.min(available, n - done);
                System.arraycopy(duplex.array(), duplex.offset(), into, at + done, take);
                duplex.advance(take);
                done += take;
            }
            return true;
        }

        private static void unmask(byte[] buf, int off, int len, byte m0, byte m1, byte m2, byte m3) {
            int i = off;
            int end = off + len;
            int end4 = off + (len & ~3);
            while (i < end4) {
                buf[i] ^= m0;
                buf[i + 1] ^= m1;
                buf[i + 2] ^= m2;
                buf[i + 3] ^= m3;
                i += 4;
            }
            if (i < end) buf[i++] ^= m0;
            if (i < end) buf[i++] ^= m1;
            if (i < end) buf[i] ^= m2;
        }
    }
}
