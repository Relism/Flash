package dev.relism.flash.ext.ndjson;

import dev.relism.flash.ext.serde.Decoder;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.HttpException;
import java.io.IOException;
import java.util.Arrays;

/**
 * The request's lines, each decoded as it is reached. A line is decoded where the transport holds
 * it; only one split between two reads is gathered first. Empty lines are skipped, a {@code \r}
 * before the {@code \n} is dropped, and a last line without {@code \n} is read too.
 */
public final class NdjsonInput<T> {

    /** The longest line accepted, in bytes. */
    public static final int MAX_LINE = 1024 * 1024;

    private final Duplex duplex;
    private final Decoder decoder;
    private byte[] gathered = new byte[0];
    private int held; // bytes of a line split between two reads
    private long line;
    private boolean ended;

    NdjsonInput(Duplex duplex, Decoder decoder) {
        this.duplex = duplex;
        this.decoder = decoder;
    }

    /** The next value, or {@code null} at the end of the body. Always {@code null} when the handler reads {@code Void}. */
    public T next() throws IOException {
        while (decoder != null && !ended) {
            int available = duplex.fill();
            if (available < 0) {
                ended = true;
                if (held > 0) return decode(gathered, 0, held);
                return null;
            }
            byte[] bytes = duplex.array();
            int start = duplex.offset();
            int newline = start;
            while (newline < start + available && bytes[newline] != '\n') newline++;
            if (newline == start + available) {
                gather(bytes, start, available);
                duplex.advance(available);
                continue;
            }
            T value;
            if (held == 0) {
                value = decode(bytes, start, newline - start);
            } else {
                gather(bytes, start, newline - start);
                value = decode(gathered, 0, held);
            }
            duplex.advance(newline - start + 1);
            if (value != null) return value;
        }
        return null;
    }

    private void gather(byte[] bytes, int offset, int length) {
        if (held + length > MAX_LINE) throw tooLong();
        if (gathered.length < held + length)
            gathered = Arrays.copyOf(gathered, Math.max(held + length, Math.max(256, gathered.length * 2)));
        System.arraycopy(bytes, offset, gathered, held, length);
        held += length;
    }

    /** One line's value, or null for an empty line. */
    @SuppressWarnings("unchecked")
    private T decode(byte[] bytes, int offset, int length) throws IOException {
        if (length > MAX_LINE) throw tooLong();
        held = 0;
        line++;
        if (length > 0 && bytes[offset + length - 1] == '\r') length--;
        if (length == 0) return null;
        Object value;
        try {
            value = decoder.decode(bytes, offset, length);
        } catch (HttpException refused) {
            if (refused.status() == 400) throw HttpException.badRequest("NDJSON line " + line + ": " + refused.getMessage());
            throw refused;
        } catch (IOException | RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException(failure);
        }
        if (value == null) throw HttpException.badRequest("NDJSON line " + line + " is null");
        return (T) value;
    }

    private HttpException tooLong() {
        return new HttpException(413, "NDJSON line " + (line + 1) + " exceeds " + MAX_LINE + " bytes");
    }
}
