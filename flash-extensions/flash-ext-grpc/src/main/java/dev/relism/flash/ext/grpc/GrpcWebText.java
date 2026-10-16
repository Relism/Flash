package dev.relism.flash.ext.grpc;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http.Duplex;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The call's duplex in base64, for {@code application/grpc-web-text}. Each write leaves as one
 * padded chunk, as the protocol allows; reads decode four characters at a time, so a chunk the
 * client padded may end anywhere. {@code java.util.Base64} only takes whole arrays, hence the table.
 */
final class GrpcWebText extends Duplex {
    private static final byte[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] VALUES = new byte[256];

    static {
        Arrays.fill(VALUES, (byte) -1);
        for (int i = 0; i < ALPHABET.length; i++) VALUES[ALPHABET[i]] = (byte) i;
    }

    private final Duplex raw;
    private final ByteWriter encoded = new ByteWriter(256);
    private final byte[] decoded = new byte[3 * 1024];
    private final byte[] quantum = new byte[4];
    private int held; // characters of a quantum split between two reads
    private int position;
    private int limit;

    GrpcWebText(Duplex raw) {
        this.raw = raw;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        encoded.reset();
        int at = encoded.reserve((len + 2) / 3 * 4);
        byte[] to = encoded.array();
        for (int end = off + len; off < end; off += 3) {
            int n = Math.min(3, end - off);
            int bits = (b[off] & 0xFF) << 16 | (n > 1 ? (b[off + 1] & 0xFF) << 8 : 0) | (n > 2 ? b[off + 2] & 0xFF : 0);
            to[at++] = ALPHABET[bits >>> 18];
            to[at++] = ALPHABET[(bits >>> 12) & 63];
            to[at++] = n > 1 ? ALPHABET[(bits >>> 6) & 63] : (byte) '=';
            to[at++] = n > 2 ? ALPHABET[bits & 63] : (byte) '=';
        }
        raw.write(to, 0, encoded.length());
    }

    @Override
    public void flush() throws IOException {
        raw.flush();
    }

    @Override
    public int fill() throws IOException {
        while (position == limit) {
            int available = raw.fill();
            if (available < 0) {
                if (held != 0) throw new EOFException("gRPC-Web text request ended mid-quantum");
                return -1;
            }
            byte[] from = raw.array();
            int start = raw.offset();
            int at = start;
            position = limit = 0;
            while (at < start + available && limit + 3 <= decoded.length) {
                quantum[held++] = from[at++];
                if (held == 4) {
                    limit += decode(limit);
                    held = 0;
                }
            }
            raw.advance(at - start);
        }
        return limit - position;
    }

    private int decode(int into) {
        boolean two = quantum[2] == '=';
        boolean one = quantum[3] == '=';
        if (two && !one) throw malformed();
        int bits = value(quantum[0]) << 18 | value(quantum[1]) << 12
                | (two ? 0 : value(quantum[2]) << 6) | (one ? 0 : value(quantum[3]));
        decoded[into] = (byte) (bits >>> 16);
        if (two) return 1;
        decoded[into + 1] = (byte) (bits >>> 8);
        if (one) return 2;
        decoded[into + 2] = (byte) bits;
        return 3;
    }

    private static int value(byte character) {
        int value = VALUES[character & 0xFF];
        if (value < 0) throw malformed();
        return value;
    }

    private static GrpcException malformed() {
        return new GrpcException(Status.INVALID_ARGUMENT, "gRPC-Web text request is not base64");
    }

    @Override
    public byte[] array() {
        return decoded;
    }

    @Override
    public int offset() {
        return position;
    }

    @Override
    public void advance(int n) {
        position += n;
    }
}
