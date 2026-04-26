package dev.relism.flash;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

/**
 * De-chunking {@link InputStream} for HTTP/1.1 {@code Transfer-Encoding: chunked} request bodies.
 * Handles pre-buffered bytes from the header read-ahead, chunk framing, and trailer consumption.
 * Returns -1 at end of the final chunk; the underlying socket is left positioned for the next request.
 */
final class ChunkedInputStream extends InputStream {
    private final InputStream src;
    private int     chunkRemaining = 0;
    private boolean done           = false;

    ChunkedInputStream(InputStream socket, byte[] preBuf, int preBufOff, int preBufLen) {
        src = preBufLen > 0
                ? new SequenceInputStream(new ByteArrayInputStream(preBuf, preBufOff, preBufLen), socket)
                : socket;
    }

    @Override
    public int read() throws IOException {
        if (done) return -1;
        while (chunkRemaining == 0) {
            chunkRemaining = readChunkSize();
            if (chunkRemaining == 0) { consumeTrailers(); done = true; return -1; }
        }
        int b = src.read();
        if (b >= 0 && --chunkRemaining == 0) { src.read(); src.read(); } // consume trailing \r\n
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
            if (chunkRemaining == 0) { src.read(); src.read(); } // consume trailing \r\n
        }
        return n;
    }

    private int readChunkSize() throws IOException {
        long size = 0;
        int  b;
        while ((b = src.read()) != -1) {
            if      (b >= '0' && b <= '9') size = (size << 4) | (b - '0');
            else if (b >= 'a' && b <= 'f') size = (size << 4) | (b - 'a' + 10);
            else if (b >= 'A' && b <= 'F') size = (size << 4) | (b - 'A' + 10);
            else { while ((b = src.read()) != -1 && b != '\n'); break; } // ext or \r\n
            if (size > Integer.MAX_VALUE) throw new IOException("Chunk size exceeds 2 GB limit");
        }
        return (int) size;
    }

    // Reads and discards trailer headers until the empty line that terminates the chunked body.
    private void consumeTrailers() throws IOException {
        while (true) {
            int b = src.read();
            if (b == -1 || b == '\r') { src.read(); return; } // empty line — done
            while ((b = src.read()) != -1 && b != '\n');      // skip non-empty trailer line
        }
    }
}
