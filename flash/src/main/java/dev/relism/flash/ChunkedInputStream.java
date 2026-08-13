package dev.relism.flash;

import dev.relism.flash.exceptions.MalformedRequestException;
import dev.relism.flash.http.Http1Limits;
import dev.relism.flash.models.BodyCompletion;
import dev.relism.flash.models.MutableHeaderMap;
import dev.relism.flash.transport.BufferedByteSource;

import java.io.IOException;
import java.io.InputStream;

/**
 * De-chunking {@link InputStream} for HTTP/1.1 {@code Transfer-Encoding: chunked} request bodies.
 * Handles pre-buffered bytes from the header read-ahead, chunk framing, and trailer consumption.
 * Returns -1 at end of the final chunk; the underlying socket is left positioned for the next request.
 *
 * instead of the raw, unbuffered socket stream. Chunk-size digits, the trailing CRLF after each
 * chunk, and trailer lines are all read one byte at a time by design (the framing is
 * byte-oriented) — that used to mean one {@code read(2)} syscall per byte on the raw socket;
 * against {@link BufferedByteSource} it is a read from an already-filled in-memory buffer.
 * The header-parser's read-ahead bytes are handed to {@code src} via
 * {@link BufferedByteSource#prependOnce}, replacing the {@code SequenceInputStream}/
 * {@code ByteArrayInputStream} pair the previous implementation allocated per chunked request.
 */
final class ChunkedInputStream extends InputStream implements BodyCompletion {
    private final BufferedByteSource src;
    private int     chunkRemaining = 0;
    private boolean done           = false;
    private int     chunksSeen     = 0;
    private final MutableHeaderMap trailers;
    private final byte[] trailerLine = new byte[Http1Limits.MAX_HEADER_VALUE_LENGTH];

    ChunkedInputStream(BufferedByteSource src, byte[] preBuf, int preBufOff, int preBufLen,
                       MutableHeaderMap trailers) {
        this.src = src;
        this.trailers = trailers;
        if (preBufLen > 0) src.prependOnce(preBuf, preBufOff, preBufLen);
    }

    ChunkedInputStream(BufferedByteSource src, byte[] preBuf, int preBufOff, int preBufLen) {
        this(src, preBuf, preBufOff, preBufLen, new MutableHeaderMap());
    }

    @Override
    public boolean fullyRead() {
        return done;
    }

    @Override
    public int read() throws IOException {
        if (done) return -1;
        while (chunkRemaining == 0) {
            chunkRemaining = readChunkSize();
            if (chunkRemaining == 0) { consumeTrailers(); done = true; return -1; }
        }
        int b = src.read();
        if (b >= 0 && --chunkRemaining == 0) consumeChunkTerminator();
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (done) return -1;
        while (chunkRemaining == 0) {
            chunkRemaining = readChunkSize();
            if (chunkRemaining == 0) { consumeTrailers(); done = true; return -1; }
        }
        int n = src.read(buf, off, Math.min(len, chunkRemaining));
        if (n > 0) {
            chunkRemaining -= n;
            if (chunkRemaining == 0) consumeChunkTerminator();
        }
        return n;
    }

    /** Validates and consumes the CRLF that terminates every chunk's data (RFC 9112 §7.1.1). */
    private void consumeChunkTerminator() throws IOException {
        int cr = src.read();
        int lf = src.read();
        if (cr != '\r' || lf != '\n') {
            throw new MalformedRequestException(400, "Malformed chunk terminator");
        }
    }

    /**
     * Reads one chunk-size line: hex digits, an optional {@code ;}-prefixed chunk-extension
     * (discarded — RFC 9112 §7.1.1 permits ignoring extensions this server does not recognise),
     * then CRLF. Bounded per {@code Http1Limits} against: more than 16 hex digits (a chunk size
     * cannot legitimately need more — {@code Long.MAX_VALUE} is 16 hex digits), a size above
     * {@link Http1Limits#MAX_CHUNK_SIZE}, an extension longer than
     * {@link Http1Limits#MAX_CHUNK_EXT_LENGTH}, and more than
     * {@link Http1Limits#MAX_CHUNKS_PER_BODY} chunks per body — all defences against a peer
     * that is technically well-formed but deliberately expensive to parse.
     */
    private int readChunkSize() throws IOException {
        if (++chunksSeen > Http1Limits.MAX_CHUNKS_PER_BODY) {
            throw new MalformedRequestException(413, "Too many chunks");
        }

        long size = 0;
        int  digits = 0;
        int  b = src.read();
        while (isHexDigit(b)) {
            if (++digits > 16) throw new MalformedRequestException(400, "Chunk size line too long");
            size = (size << 4) | hexValue(b);
            if (size > Http1Limits.MAX_CHUNK_SIZE) {
                throw new MalformedRequestException(413, "Chunk size exceeds configured maximum");
            }
            b = src.read();
        }
        if (digits == 0) throw new MalformedRequestException(400, "Malformed chunk size");

        int extLen = 0;
        while (b != -1 && b != '\r') {
            if (++extLen > Http1Limits.MAX_CHUNK_EXT_LENGTH) {
                throw new MalformedRequestException(400, "Chunk extension too long");
            }
            b = src.read();
        }
        if (b != '\r' || src.read() != '\n') {
            throw new MalformedRequestException(400, "Malformed chunk size line terminator");
        }
        return (int) size;
    }

    private static boolean isHexDigit(int b) {
        return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
    }

    private static int hexValue(int b) {
        if (b <= '9') return b - '0';
        if (b <= 'F') return b - 'A' + 10;
        return b - 'a' + 10;
    }

    /**
     * Reads and discards trailer headers until the empty line that terminates the chunked body
     * (RFC 9112 §7.1.2). Bounded by {@link Http1Limits#MAX_TRAILER_COUNT} and
     * {@link Http1Limits#MAX_HEADER_VALUE_LENGTH} — without a bound, a peer could follow the
     * final chunk with an unbounded trailer section purely to waste CPU discarding it. Trailers
     * ({@code Request.trailers()}).
     */
    private void consumeTrailers() throws IOException {
        int trailerCount = 0;
        while (true) {
            int b = src.read();
            if (b == -1) throw new MalformedRequestException(400, "Truncated trailer section");
            if (b == '\r') {
                if (src.read() != '\n') {
                    throw new MalformedRequestException(400, "Malformed trailer section terminator");
                }
                return; // empty line — trailer section done
            }
            if (++trailerCount > Http1Limits.MAX_TRAILER_COUNT) {
                throw new MalformedRequestException(431, "Too many trailers");
            }
            int lineLen = 0;
            trailerLine[lineLen++] = (byte) b;
            while ((b = src.read()) != -1 && b != '\n') {
                if (lineLen == trailerLine.length) {
                    throw new MalformedRequestException(431, "Trailer line too long");
                }
                trailerLine[lineLen++] = (byte) b;
            }
            if (b != '\n' || lineLen == 0 || trailerLine[lineLen - 1] != '\r') {
                throw new MalformedRequestException(400, "Malformed trailer line");
            }
            addTrailer(lineLen - 1);
        }
    }

    private void addTrailer(int lineLength) throws MalformedRequestException {
        int colon = -1;
        for (int i = 0; i < lineLength; i++) {
            if (trailerLine[i] == ':') { colon = i; break; }
        }
        if (colon <= 0) throw new MalformedRequestException(400, "Malformed trailer field");
        for (int i = 0; i < colon; i++) {
            int c = trailerLine[i] & 0xff;
            if (!isToken(c)) {
                throw new MalformedRequestException(400, "Invalid trailer field name");
            }
        }
        int valueStart = colon + 1;
        while (valueStart < lineLength
            && (trailerLine[valueStart] == ' ' || trailerLine[valueStart] == '\t')) valueStart++;
        int valueEnd = lineLength;
        while (valueEnd > valueStart
            && (trailerLine[valueEnd - 1] == ' ' || trailerLine[valueEnd - 1] == '\t')) valueEnd--;
        if (forbidden(trailerLine, colon)) {
            throw new MalformedRequestException(400, "Forbidden trailer field");
        }
        trailers.add(trailerLine, 0, colon, trailerLine, valueStart, valueEnd - valueStart);
    }

    private static boolean isToken(int c) {
        return (c >= '0' && c <= '9')
            || (c >= 'A' && c <= 'Z')
            || (c >= 'a' && c <= 'z')
            || c == '!' || c == '#' || c == '$' || c == '%' || c == '&' || c == '\''
            || c == '*' || c == '+' || c == '-' || c == '.' || c == '^' || c == '_'
            || c == '`' || c == '|' || c == '~';
    }

    private static boolean forbidden(byte[] name, int length) {
        return asciiEquals(name, length, "content-length")
            || asciiEquals(name, length, "transfer-encoding")
            || asciiEquals(name, length, "host")
            || asciiEquals(name, length, "trailer");
    }

    private static boolean asciiEquals(byte[] bytes, int length, String expected) {
        if (length != expected.length()) return false;
        for (int i = 0; i < length; i++) {
            int c = bytes[i] & 0xff;
            if (c >= 'A' && c <= 'Z') c += 32;
            if (c != expected.charAt(i)) return false;
        }
        return true;
    }
}
