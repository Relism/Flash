package dev.relism.flash.bytes;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Index-based writer into a growable {@code byte[]} scratch buffer. Every {@code write*} method
 * bounds-checks and grows the backing array only when the write would not otherwise fit —
 * on an already-warm buffer (the steady-state case: the buffer has already grown to the
 * connection's high-water mark), no method here allocates.
 *
 * Callers build a complete message in a {@code ByteWriter}-backed scratch buffer and then issue
 * one bulk {@code write(buffer, 0, length())}. It is an {@link OutputStream} too, so a serializer
 * that writes to one writes straight into the buffer. The same writer is shared by HTTP/1.1 and HTTP/2.
 *
 * <h3>Lifetime and thread-safety contract</h3>
 * Not thread-safe — exactly one writer at a time, matching every other per-connection scratch
 * object in this codebase ({@code ConnectionScratch}, {@code Http1HeaderMap}). {@link #reset()}
 * repositions this writer to the start of its backing array for the next message; the backing
 * array itself is never shrunk back down, only grown — the same amortized-to-zero-allocation
 * growth policy {@code RequestParser}'s read buffer already uses.
 */
public final class ByteWriter extends OutputStream {
    private byte[] buf;
    private final byte[] digits = new byte[20];
    private int len;

    public ByteWriter(int initialCapacity) {
        this.buf = new byte[Math.max(initialCapacity, 16)];
    }

    /** Repositions this writer to the start of its buffer, ready for the next message. */
    public void reset() {
        len = 0;
    }

    /** The backing buffer. Valid content is {@code [0, length())} — never assume {@code buf.length == length()}. */
    public byte[] array() {
        return buf;
    }

    /** How many bytes have been written since the last {@link #reset()}. */
    public int length() {
        return len;
    }

    private void ensure(int additional) {
        int needed = len + additional;
        if (needed <= buf.length) return;
        int grown = buf.length * 2;
        while (grown < needed) grown *= 2;
        byte[] next = new byte[grown];
        System.arraycopy(buf, 0, next, 0, len);
        buf = next;
    }

    /** Makes room for {@code n} bytes a caller writes into {@link #array()} itself, and returns where they start. */
    public int reserve(int n) {
        ensure(n);
        int at = len;
        len += n;
        return at;
    }

    public void writeByte(byte b) {
        ensure(1);
        buf[len++] = b;
    }

    @Override
    public void write(int b) {
        writeByte((byte) b);
    }

    @Override
    public void write(byte[] src, int off, int srcLen) {
        writeBytes(src, off, srcLen);
    }

    public void writeBytes(byte[] src) {
        writeBytes(src, 0, src.length);
    }

    public void writeBytes(byte[] src, int off, int srcLen) {
        ensure(srcLen);
        System.arraycopy(src, off, buf, len, srcLen);
        len += srcLen;
    }

    /**
     * Writes {@code value}'s ASCII decimal digits (no sign — callers write {@code '-'} via
     * {@link #writeByte} first if needed). {@code value} must be non-negative.
     */
    public void writeDecimal(long value) {
        if (value < 0) throw new IllegalArgumentException("writeDecimal requires a non-negative value: " + value);
        if (value == 0) {
            writeByte((byte) '0');
            return;
        }
        // Digits emerge least-significant-first. The reusable field holds every possible long
        // representation, so decimal rendering does not allocate on a warm writer.
        int n = 0;
        long v = value;
        while (v > 0) {
            digits[n++] = (byte) ('0' + (v % 10));
            v /= 10;
        }
        ensure(n);
        for (int i = n - 1; i >= 0; i--) buf[len++] = digits[i];
    }

    private static final byte[] HEX_DIGITS = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    /** Writes {@code value}'s lowercase hex digits, no leading zeros (except for {@code value == 0}, which writes {@code "0"}). */
    public void writeHex(int value) {
        if (value == 0) {
            writeByte((byte) '0');
            return;
        }
        int n = 0;
        int v = value;
        while (v != 0) {
            digits[n++] = HEX_DIGITS[v & 0xF];
            v >>>= 4;
        }
        ensure(n);
        for (int i = n - 1; i >= 0; i--) buf[len++] = digits[i];
    }

    /** Writes {@code s}'s ASCII bytes, lower-cased. {@code s} must be ASCII-only. */
    public void writeAsciiLower(String s) {
        int n = s.length();
        ensure(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') c += 32;
            buf[len++] = (byte) c;
        }
    }

    /**
     * Writes {@code s}'s ASCII bytes, case preserved. {@code s} must be ASCII-only. Unlike
     * {@code new String(...).getBytes(UTF_8)}, writes each character directly into this buffer
     * and avoids an intermediate {@code byte[]}.
     */
    public void writeAscii(String s) {
        int n = s.length();
        ensure(n);
        for (int i = 0; i < n; i++) {
            buf[len++] = (byte) s.charAt(i);
        }
    }

    /** Big-endian 16-bit write — an HTTP/2 frame's stream-dependent fields, SETTINGS values, etc. */
    public void writeUInt16(int value) {
        ensure(2);
        buf[len++] = (byte) (value >>> 8);
        buf[len++] = (byte) value;
    }

    /** Big-endian 24-bit write — an HTTP/2 frame header's length field. */
    public void writeUInt24(int value) {
        ensure(3);
        buf[len++] = (byte) (value >>> 16);
        buf[len++] = (byte) (value >>> 8);
        buf[len++] = (byte) value;
    }

    /** Big-endian 31-bit write (top bit always 0) — an HTTP/2 stream identifier. */
    public void writeUInt31(int value) {
        writeUInt32(value & 0x7FFFFFFF);
    }

    /** Big-endian 32-bit write — an HTTP/2 window-size increment, SETTINGS value, etc. */
    public void writeUInt32(int value) {
        ensure(4);
        buf[len++] = (byte) (value >>> 24);
        buf[len++] = (byte) (value >>> 16);
        buf[len++] = (byte) (value >>> 8);
        buf[len++] = (byte) value;
    }
}
