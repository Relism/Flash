package dev.relism.flash.websocket;

import dev.relism.flash.http.Request;

import javax.net.ssl.SSLSession;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-connection WebSocket I/O state. One instance per virtual thread.
 *
 * <ul>
 *   <li>
 *       <p>With {@code TCP_NODELAY} enabled on the socket (set by the connection runner),
 *       Nagle's algorithm is disabled: the kernel sends data as soon as it lands in
 *       the send buffer, without waiting. {@link java.io.BufferedOutputStream} will
 *       still batch multiple small writes into one syscall when they happen in the
 *       same virtual-thread scheduling quantum — giving us coalescing where it's
 *       free, and immediate delivery where it matters.
 *
 *       <p>The only place an explicit flush is still needed is after the WS
 *       handshake, which alone writes through a buffered stream;
 *       {@code WebSocketUpgrade#performHandshake} does it there.</li>
 *
 *   <li><b>CLOSE frame</b>: the last thing written before the stream is abandoned,
 *       and it needs no flush either — {@code out} is the raw socket stream the
 *       connection handed over, so the bytes are already in the kernel buffer.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * {@link #sendText}, {@link #send}, and {@link #close} are serialized on a
 * blocking inside {@code synchronized} pins its carrier platform thread on Java 21, and a
 * blocking socket write is exactly the kind of call that can block. {@link ReentrantLock}
 * unmounts the blocked virtual thread instead) and are safe to call from threads other than the
 * session loop. {@link #close} uses a CAS on {@code open} to guarantee exactly-once CLOSE
 * frame emission under concurrent calls.
 *
 * <h3>Fragmentation, masking, and control frames (RFC 6455 §5)</h3>
 * {@link #readFrame} reassembles continuation frames into one logical message (bounded by the
 * read buffer's capacity — the same bound a single unfragmented frame already had), enforces
 * that incoming frames are masked exactly when this session's role requires it (server sessions
 * require masked frames from the client; client-mode sessions require unmasked frames from the
 * server), validates the opcode against RFC 6455's defined set, and enforces the control-frame
 * constraints (FIN must be set, payload ≤ 125 bytes). A violation throws
 * {@link WebSocketProtocolException} carrying the correct close code (1002 protocol error, 1009
 * message too big) for the caller to send before closing.
 */
public final class WebSocketSession {

    private final InputStream   in;
    private final OutputStream  out;
    private final byte[]        readBuf;
    private final Request       request;
    private final boolean       maskOutgoing;

    /** Server sessions (the common case) require every incoming frame to be masked, per RFC
     *  6455 §5.1 ("a server MUST close the connection upon receiving a frame that is not
     *  masked"). A client-mode session ({@link #maskOutgoing} true) requires the opposite. */
    private final boolean       requireMaskedIncoming;

    private final AtomicBoolean open      = new AtomicBoolean(true);
    private       int           closeCode = 1000;
    private final ReentrantLock writeLock = new ReentrantLock();

    /** 1 opcode byte + up to 8 extended-length bytes + up to 4 mask-key bytes (masked mode only). */
    private final byte[] hdrScratch = new byte[14];

    /** Scratch for control-frame payloads (RFC 6455 §5.5: at most 125 bytes), kept separate
     *  from {@link #readBuf} so a control frame arriving mid-fragmentation (RFC 6455 §5.4
     *  permits this) never disturbs the data message being reassembled there. */
    private final byte[] controlBuf = new byte[125];

    // Fragmentation state (RFC 6455 §5.4). fragmentOpcode == 0 means "no message in progress":
    // the length cannot say it, because a message may be assembled out of empty fragments.
    private byte fragmentOpcode;
    private int  fragmentLength;

    /** UTF-8 state carried across the fragments of the text message being assembled (§8.1). */
    private int utf8State = Utf8.ACCEPT;

    public WebSocketSession(InputStream in, OutputStream out, int bufferSize) {
        this(in, out, bufferSize, null, false);
    }

    /**
     * @param request      the HTTP request that upgraded to this session, or {@code null} if the
     *                     caller has no use for it (e.g. a session opened as a WS client rather
     *                     than accepted as a WS server). Stored as-is, no copy.
     * @param maskOutgoing {@code true} if this session is acting as a WS <em>client</em> — RFC 6455
     *                     requires client-to-server frames to be masked, unlike the server-to-client
     *                     direction {@link #writeFrame} originally only supported. See {@link
     *                     #writeFrame} for how masking is applied without allocating. Also
     *                     determines the expected masking of *incoming* frames — see
     *                     {@link #requireMaskedIncoming}.
     */
    public WebSocketSession(InputStream in, OutputStream out, int bufferSize, Request request, boolean maskOutgoing) {
        this.in                    = in;
        this.out                   = out;
        this.readBuf               = new byte[bufferSize];
        this.request               = request;
        this.maskOutgoing          = maskOutgoing;
        this.requireMaskedIncoming = !maskOutgoing;
    }

    public boolean isOpen()    { return open.get(); }
    public int     closeCode() { return closeCode; }

    /** The request that upgraded this connection, or {@code null} — see the 4-arg constructor. */
    public Request request() { return request; }

    /**
     * Whether this connection is TLS (WSS). Delegates to the upgrading {@link #request}'s
     * {@link Request#isSecure()} rather than tracking the socket a second time — the request
     * already carries it. {@code false} if this session has no backing request (e.g. one opened
     * in WS *client* mode via the 5-arg constructor with {@code request == null}).
     */
    public boolean isSecure() { return request != null && request.isSecure(); }

    /** TLS session for this connection, or {@code null} for plain WS or no backing request. */
    public SSLSession sslSession() { return request != null ? request.sslSession() : null; }

    // ── Public send API ────────────────────────────────────────────────────

    public void sendText(byte[] utf8, int off, int len) throws IOException {
        writeFrame(WebSocketFrame.OP_TEXT, utf8, off, len);
    }

    public void send(byte[] payload, int off, int len) throws IOException {
        writeFrame(WebSocketFrame.OP_BINARY, payload, off, len);
    }

    /**
     * Sends a CLOSE frame exactly once, masked exactly like any other outgoing frame when this
     * session is in client mode ({@link #maskOutgoing}) — via {@link #writeFrame}, the same path
     * {@link #sendText}/{@link #send}/{@link #sendPong} use, rather than hand-writing the 4 bytes
     * unmasked regardless of role. A client-mode session that skipped masking here was sending an
     * RFC-6455-invalid CLOSE frame that a strict peer's {@link #readFrame} correctly rejects (the
     * masking check {@link #readFrame} itself enforces on the receive side).
     * No flush needed — {@code out} is the raw socket OutputStream (not buffered);
     * each write goes directly to the kernel send buffer, and TCP_NODELAY ensures
     * it's transmitted immediately.
     */
    public void close(int code) throws IOException {
        if (!open.compareAndSet(true, false)) return;
        byte[] payload = {(byte) ((code >> 8) & 0xFF), (byte) (code & 0xFF)};
        writeFrame(WebSocketFrame.OP_CLOSE, payload, 0, 2);
    }

    // ── Session loop internals ─────────────────────────────────────────────

    /**
     * Reads the next complete message, reassembling continuation frames and delivering control
     * frames (CLOSE/PING/PONG) as soon as they arrive — RFC 6455 §5.4 explicitly permits a
     * control frame to interleave with a fragmented data message, and this must not disturb the
     * data message's in-progress reassembly.
     *
     * @return {@code false} only on a clean EOF between messages (the peer closed the TCP
     *         connection without sending a CLOSE frame); an EOF in the middle of a frame is a
     *         protocol violation and throws, it is not reported as {@code false}.
     * @throws WebSocketProtocolException on any RFC 6455 violation (bad opcode, unmasked/masked
     *         frame when the opposite was required, oversized control frame, fragmented control
     *         frame, message exceeding the buffer) — carries the correct close code.
     */
    public boolean readFrame(WebSocketFrame frame) throws IOException {
        while (true) {
            int b0 = in.read();
            if (b0 < 0) return false; // clean EOF between messages
            int b1 = in.read();
            if (b1 < 0) throw new EOFException("WebSocket stream closed mid-frame");

            boolean fin     = (b0 & 0x80) != 0;
            byte    opcode  = (byte) (b0 & 0x0F);
            boolean masked  = (b1 & 0x80) != 0;
            int     lenBits = b1 & 0x7F;

            // RFC 6455 §5.2: RSV1/2/3 mean something only to a negotiated extension, and this
            // endpoint negotiates none, so any of them set is a protocol error.
            if ((b0 & 0x70) != 0) throw new WebSocketProtocolException(1002, "RSV bits must be zero");

            validateOpcode(opcode);

            if (masked != requireMaskedIncoming) {
                throw new WebSocketProtocolException(1002,
                        requireMaskedIncoming ? "client frame must be masked" : "server frame must not be masked");
            }

            int extLenBytes = lenBits == 127 ? 8 : lenBits == 126 ? 2 : 0;
            int maskBytes   = masked ? 4 : 0;
            int extraLen    = extLenBytes + maskBytes;
            if (extraLen > 0) readFullyHeader(extraLen);

            long payLen;
            int  pos;
            if (extLenBytes == 2) {
                payLen = ((hdrScratch[0] & 0xFFL) << 8) | (hdrScratch[1] & 0xFFL);
                pos = 2;
            } else if (extLenBytes == 8) {
                // RFC 6455 §5.2: the most significant bit of the 64-bit length MUST be 0.
                if ((hdrScratch[0] & 0x80) != 0) {
                    throw new WebSocketProtocolException(1002, "extended payload length MSB must be 0");
                }
                payLen = 0;
                for (int i = 0; i < 8; i++) payLen = (payLen << 8) | (hdrScratch[i] & 0xFFL);
                pos = 8;
            } else {
                payLen = lenBits;
                pos = 0;
            }

            boolean isControl = opcode == WebSocketFrame.OP_CLOSE
                    || opcode == WebSocketFrame.OP_PING || opcode == WebSocketFrame.OP_PONG;

            if (isControl) {
                if (!fin) throw new WebSocketProtocolException(1002, "control frame must not be fragmented");
                if (payLen > controlBuf.length) throw new WebSocketProtocolException(1002, "control frame payload exceeds 125 bytes");
            } else if (opcode == WebSocketFrame.OP_CONTINUATION) {
                if (fragmentOpcode == 0) throw new WebSocketProtocolException(1002, "continuation frame without an initiated message");
            } else { // TEXT or BINARY
                if (fragmentOpcode != 0) throw new WebSocketProtocolException(1002, "new data frame while a fragmented message is in progress");
            }

            byte m0 = 0, m1 = 0, m2 = 0, m3 = 0;
            if (masked) {
                m0 = hdrScratch[pos]; m1 = hdrScratch[pos + 1]; m2 = hdrScratch[pos + 2]; m3 = hdrScratch[pos + 3];
            }

            int len = (int) payLen;

            if (isControl) {
                readFully(controlBuf, 0, len);
                if (masked) unmaskInPlace(controlBuf, 0, len, m0, m1, m2, m3);
                frame.reset(controlBuf, 0, len, opcode, true);
                return true;
            }

            // Data frame (fresh TEXT/BINARY, or a CONTINUATION of one already in progress):
            // accumulate into readBuf, bounded by its capacity — the same bound a single
            // unfragmented frame already had before this fix.
            if (fragmentLength + (long) len > readBuf.length) {
                throw new WebSocketProtocolException(1009, "message exceeds " + readBuf.length + " bytes");
            }
            readFully(readBuf, fragmentLength, len);
            if (masked) unmaskInPlace(readBuf, fragmentLength, len, m0, m1, m2, m3);

            byte messageOpcode = opcode == WebSocketFrame.OP_CONTINUATION ? fragmentOpcode : opcode;
            if (messageOpcode == WebSocketFrame.OP_TEXT) {
                // RFC 6455 §8.1: judged on the bytes as they land, so a broken fragment fails the
                // connection without waiting for the ones behind it.
                utf8State = Utf8.validate(utf8State, readBuf, fragmentLength, len);
                if (utf8State == Utf8.REJECT) throw new WebSocketProtocolException(1007, "text message is not valid UTF-8");
            }
            if (opcode != WebSocketFrame.OP_CONTINUATION) fragmentOpcode = opcode;
            fragmentLength += len;

            if (fin) {
                // A message may not end half way through a sequence; a binary one never left ACCEPT.
                if (utf8State != Utf8.ACCEPT) throw new WebSocketProtocolException(1007, "text message ends mid UTF-8 sequence");
                frame.reset(readBuf, 0, fragmentLength, messageOpcode, true);
                fragmentLength = 0;
                fragmentOpcode = 0;
                utf8State      = Utf8.ACCEPT;
                return true;
            }
            // Not FIN: loop to read the next continuation frame (or an interleaved control frame).
        }
    }

    private static void validateOpcode(byte opcode) throws WebSocketProtocolException {
        switch (opcode) {
            case WebSocketFrame.OP_CONTINUATION, WebSocketFrame.OP_TEXT, WebSocketFrame.OP_BINARY,
                 WebSocketFrame.OP_CLOSE, WebSocketFrame.OP_PING, WebSocketFrame.OP_PONG -> { /* valid */ }
            default -> throw new WebSocketProtocolException(1002, "reserved/invalid opcode " + opcode);
        }
    }

    public void sendPong(WebSocketFrame ping) throws IOException {
        writeFrame(WebSocketFrame.OP_PONG, ping.buffer(), ping.payloadOffset(), ping.payloadLength());
    }

    public void echo(WebSocketFrame frame) throws IOException {
        if (frame.opcode() == WebSocketFrame.OP_TEXT)
            sendText(frame.buffer(), frame.payloadOffset(), frame.payloadLength());
        else
            send(frame.buffer(), frame.payloadOffset(), frame.payloadLength());
    }

    /**
     * Answers the peer's CLOSE with one of ours and ends the session. RFC 6455 §5.5.1 requires the
     * reply and asks for the same status code back, so a status this endpoint refuses to receive is
     * never one it sends: the code is checked against §7.4.1 and the reason against the UTF-8 rule,
     * and a malformed frame throws for {@code WebSocketLoop} to close with the code the failure
     * carries. An empty payload has no code to echo and is answered with a normal closure.
     *
     * <p>The code is read straight out of the frame's own buffer — the payload is never copied.
     */
    public void closeFromPeer(WebSocketFrame frame) throws IOException {
        int len = frame.payloadLength();
        if (len == 0) {
            close(closeCode = 1000);
            return;
        }
        if (len == 1) throw new WebSocketProtocolException(1002, "close payload of one byte carries no status code");

        byte[] payload = frame.buffer();
        int    off     = frame.payloadOffset();
        int    code    = ((payload[off] & 0xFF) << 8) | (payload[off + 1] & 0xFF);

        if (!isSendableCloseCode(code)) throw new WebSocketProtocolException(1002, "reserved or undefined close code " + code);
        if (Utf8.validate(Utf8.ACCEPT, payload, off + 2, len - 2) != Utf8.ACCEPT)
            throw new WebSocketProtocolException(1007, "close reason is not valid UTF-8");

        close(closeCode = code);
    }

    /**
     * RFC 6455 §7.4.1: the registered codes, plus the private range. 1004 was never assigned, and
     * 1005/1006 stand for "no code" and "abnormal" — they describe a connection, they are not codes
     * that may travel on one, in either direction.
     */
    private static boolean isSendableCloseCode(int code) {
        if (code >= 3000 && code <= 4999) return true;
        return code >= 1000 && code <= 1014 && code != 1004 && code != 1005 && code != 1006;
    }

    public void forceClose() {
        open.set(false);
        try { in.close(); } catch (IOException ignored) {}
    }

    // ── Private ────────────────────────────────────────────────────────────

    /**
     * Encodes the WS frame header into {@link #hdrScratch} (at most 14 bytes: 1 opcode + up to 8
     * extended-length + up to 4 mask-key), then writes header + payload in two bulk calls to the
     * raw socket stream.
     *
     * <p>No {@code flush()} — {@code out} is the unbuffered socket {@link OutputStream}.
     * Each {@code write()} lands directly in the kernel send buffer. With {@code TCP_NODELAY}
     * set on the socket, the kernel transmits the segment immediately without Nagle coalescing.
     * The two writes (header then payload) will be merged into a single TCP segment by the
     * kernel because they arrive faster than the ACK from the peer — exactly the coalescing we
     * want, at zero cost.
     *
     * <p><b>{@link #maskOutgoing} (client mode):</b> RFC 6455 requires every client-to-server frame
     * to be masked. The mask key is generated into {@link #hdrScratch} (no new allocation — same
     * fixed field every frame reuses) and the payload is masked <em>in place</em> via {@link
     * #unmaskInPlace} — XOR is its own inverse, so the exact routine {@link #readFrame} already
     * uses to unmask an inbound payload masks an outbound one too, with no separate code path and
     * no copy. This mutates the caller's {@code payload} array as a side effect: callers using
     * masked mode must not reuse that buffer expecting it unchanged after the call.
     */
    private void writeFrame(byte opcode, byte[] payload, int off, int len) throws IOException {
        writeLock.lock();
        try {
            int hlen = 0;
            hdrScratch[hlen++] = (byte) (0x80 | opcode);
            int maskBit = maskOutgoing ? 0x80 : 0x00;
            if (len <= 125) {
                hdrScratch[hlen++] = (byte) (maskBit | len);
            } else if (len <= 0xFFFF) {
                hdrScratch[hlen++] = (byte) (maskBit | 126);
                hdrScratch[hlen++] = (byte) ((len >> 8) & 0xFF);
                hdrScratch[hlen++] = (byte)  (len       & 0xFF);
            } else {
                hdrScratch[hlen++] = (byte) (maskBit | 127);
                hdrScratch[hlen++] = 0; hdrScratch[hlen++] = 0;
                hdrScratch[hlen++] = 0; hdrScratch[hlen++] = 0;
                hdrScratch[hlen++] = (byte) ((len >> 24) & 0xFF);
                hdrScratch[hlen++] = (byte) ((len >> 16) & 0xFF);
                hdrScratch[hlen++] = (byte) ((len >>  8) & 0xFF);
                hdrScratch[hlen++] = (byte)  (len        & 0xFF);
            }
            if (maskOutgoing) {
                // ThreadLocalRandom needs no seeding/allocation per call; the four mask bytes are
                // carved out of one int, never boxed.
                int  mask = ThreadLocalRandom.current().nextInt();
                byte m0 = (byte) (mask >>> 24), m1 = (byte) (mask >>> 16), m2 = (byte) (mask >>> 8), m3 = (byte) mask;
                hdrScratch[hlen++] = m0;
                hdrScratch[hlen++] = m1;
                hdrScratch[hlen++] = m2;
                hdrScratch[hlen++] = m3;
                unmaskInPlace(payload, off, len, m0, m1, m2, m3);
            }
            out.write(hdrScratch, 0, hlen);
            out.write(payload, off, len);
            // No flush — TCP_NODELAY handles delivery. See Javadoc above.
        } finally {
            writeLock.unlock();
        }
    }

    /** Bulk-reads {@code len} bytes into {@link #hdrScratch} starting at offset 0. */
    private void readFullyHeader(int len) throws IOException {
        int remaining = len;
        while (remaining > 0) {
            int n = in.read(hdrScratch, len - remaining, remaining);
            if (n < 0) throw new EOFException("WebSocket stream closed mid-frame");
            remaining -= n;
        }
    }

    private void readFully(byte[] buf, int off, int len) throws IOException {
        int remaining = len;
        while (remaining > 0) {
            int n = in.read(buf, off + (len - remaining), remaining);
            if (n < 0) throw new EOFException("WebSocket stream closed mid-frame");
            remaining -= n;
        }
    }

    private static void unmaskInPlace(byte[] buf, int off, int len,
                                      byte m0, byte m1, byte m2, byte m3) {
        int i    = off;
        int end  = off + len;
        int end4 = off + (len & ~3);
        while (i < end4) {
            buf[i]   ^= m0;
            buf[i+1] ^= m1;
            buf[i+2] ^= m2;
            buf[i+3] ^= m3;
            i += 4;
        }
        if (i < end) { buf[i++] ^= m0; }
        if (i < end) { buf[i++] ^= m1; }
        if (i < end) { buf[i]   ^= m2; }
    }
}
