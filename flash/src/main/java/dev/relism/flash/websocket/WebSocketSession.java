package dev.relism.flash.websocket;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-connection WebSocket I/O state. One instance per virtual thread.
 *
 * <ul>
 *   <li>
 *       <p>With {@code TCP_NODELAY} enabled on the socket (set in {@code HttpServer}),
 *       Nagle's algorithm is disabled: the kernel sends data as soon as it lands in
 *       the send buffer, without waiting. {@link java.io.BufferedOutputStream} will
 *       still batch multiple small writes into one syscall when they happen in the
 *       same virtual-thread scheduling quantum — giving us coalescing where it's
 *       free, and immediate delivery where it matters.
 *
 *       <p>The only place an explicit flush is still needed is after the WS
 *       handshake (one-time, not on the hot path) and after the CLOSE frame
 *       (end of session). Both are handled in {@link #close} and in
 *       {@code HttpServer#performHandshake}.</li>
 *
 *   <li><b>Flush on CLOSE frame</b>: {@link #close} still flushes explicitly
 *       because the CLOSE frame is the last thing written before the stream is
 *       abandoned — without a flush, the 4 bytes could sit in the buffer forever.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * {@link #sendText}, {@link #send}, and {@link #close} are synchronized on
 * {@code out} and safe to call from threads other than the session loop.
 * {@link #close} uses a CAS on {@code open} to guarantee exactly-once CLOSE
 * frame emission under concurrent calls.
 */
public final class WebSocketSession {

    private final InputStream   in;
    private final OutputStream  out;
    private final byte[]        readBuf;

    private final AtomicBoolean open      = new AtomicBoolean(true);
    private       int           closeCode = 1000;

    private final byte[] hdrScratch = new byte[10];

    public WebSocketSession(InputStream in, OutputStream out, int bufferSize) {
        this.in      = in;
        this.out     = out;
        this.readBuf = new byte[bufferSize];
    }

    public boolean isOpen()    { return open.get(); }
    public int     closeCode() { return closeCode; }

    // ── Public send API ────────────────────────────────────────────────────

    public void sendText(byte[] utf8, int off, int len) throws IOException {
        writeFrame(WebSocketFrame.OP_TEXT, utf8, off, len);
    }

    public void send(byte[] payload, int off, int len) throws IOException {
        writeFrame(WebSocketFrame.OP_BINARY, payload, off, len);
    }

    /**
     * Sends a CLOSE frame exactly once.
     * No flush needed — {@code out} is the raw socket OutputStream (not buffered);
     * each write goes directly to the kernel send buffer, and TCP_NODELAY ensures
     * it's transmitted immediately.
     */
    public void close(int code) throws IOException {
        if (!open.compareAndSet(true, false)) return;
        synchronized (out) {
            out.write(0x88);
            out.write(0x02);
            out.write((code >> 8) & 0xFF);
            out.write(code & 0xFF);
        }
    }

    // ── Session loop internals ─────────────────────────────────────────────

    public boolean readFrame(WebSocketFrame frame) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return false;
        int b1 = in.read();
        if (b1 < 0) return false;

        boolean fin    = (b0 & 0x80) != 0;
        byte    opcode = (byte) (b0 & 0x0F);
        boolean masked = (b1 & 0x80) != 0;
        long    payLen = (b1 & 0x7F);

        if (payLen == 126) {
            payLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (payLen == 127) {
            payLen = 0;
            for (int i = 0; i < 8; i++) payLen = (payLen << 8) | (in.read() & 0xFF);
        }

        if (payLen > readBuf.length) throw new IOException(
                "WS frame payload " + payLen + " bytes exceeds buffer " + readBuf.length);

        byte m0 = 0, m1 = 0, m2 = 0, m3 = 0;
        if (masked) {
            m0 = (byte) in.read(); m1 = (byte) in.read();
            m2 = (byte) in.read(); m3 = (byte) in.read();
        }

        int len = (int) payLen;
        readFully(readBuf, 0, len);

        if (masked) unmaskInPlace(readBuf, 0, len, m0, m1, m2, m3);

        frame.reset(readBuf, 0, len, opcode, fin);
        return true;
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

    public void closeFromPeer(WebSocketFrame frame) {
        if (frame.payloadLength() >= 2) {
            byte[] b = frame.copyPayload();
            int    o = frame.payloadOffset();
            closeCode = ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
        }
        open.set(false);
    }

    public void forceClose() {
        open.set(false);
        try { in.close(); } catch (IOException ignored) {}
    }

    // ── Private ────────────────────────────────────────────────────────────

    /**
     * Encodes the WS frame header into {@link #hdrScratch} (at most 10 bytes),
     * then writes header + payload in two bulk calls to the raw socket stream.
     *
     * <p>No {@code flush()} — {@code out} is the unbuffered socket {@link OutputStream}
     * (see {@code HttpServer#process}). Each {@code write()} lands directly in the
     * kernel send buffer. With {@code TCP_NODELAY} set on the socket, the kernel
     * transmits the segment immediately without Nagle coalescing. The two writes
     * (header then payload) will be merged into a single TCP segment by the kernel
     * because they arrive faster than the ACK from the peer — exactly the coalescing
     * we want, at zero cost.
     */
    private void writeFrame(byte opcode, byte[] payload, int off, int len) throws IOException {
        synchronized (out) {
            int hlen = 0;
            hdrScratch[hlen++] = (byte) (0x80 | opcode);
            if (len <= 125) {
                hdrScratch[hlen++] = (byte) len;
            } else if (len <= 0xFFFF) {
                hdrScratch[hlen++] = 126;
                hdrScratch[hlen++] = (byte) ((len >> 8) & 0xFF);
                hdrScratch[hlen++] = (byte)  (len       & 0xFF);
            } else {
                hdrScratch[hlen++] = 127;
                hdrScratch[hlen++] = 0; hdrScratch[hlen++] = 0;
                hdrScratch[hlen++] = 0; hdrScratch[hlen++] = 0;
                hdrScratch[hlen++] = (byte) ((len >> 24) & 0xFF);
                hdrScratch[hlen++] = (byte) ((len >> 16) & 0xFF);
                hdrScratch[hlen++] = (byte) ((len >>  8) & 0xFF);
                hdrScratch[hlen++] = (byte)  (len        & 0xFF);
            }
            out.write(hdrScratch, 0, hlen);
            out.write(payload, off, len);
            // No flush — TCP_NODELAY handles delivery. See Javadoc above.
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