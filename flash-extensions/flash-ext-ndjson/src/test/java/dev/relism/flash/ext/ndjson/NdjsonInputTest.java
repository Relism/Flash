package dev.relism.flash.ext.ndjson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.HttpException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The line reader on its own, fed a few bytes at a time. */
class NdjsonInputTest {

    /** Decodes a line to its text, so what was cut and joined is visible. */
    private static NdjsonInput<String> input(String body, int step) {
        return new NdjsonInput<>(new Chunks(body.getBytes(StandardCharsets.UTF_8), step),
                (bytes, offset, length) -> new String(bytes, offset, length, StandardCharsets.UTF_8));
    }

    private static List<String> lines(NdjsonInput<String> input) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line; (line = input.next()) != null; ) lines.add(line);
        return lines;
    }

    @Test
    void everySplitPointReadsTheSameLines() throws Exception {
        String body = "{\"a\":1}\r\n\n{\"b\":\"é\"}\n\r\n{\"c\":3}";
        for (int step = 1; step <= body.length() + 1; step++)
            assertEquals(List.of("{\"a\":1}", "{\"b\":\"é\"}", "{\"c\":3}"), lines(input(body, step)), "step " + step);
    }

    @Test
    void anOverlongLineIs413WhetherInPlaceOrGathered() throws Exception {
        String line = "x".repeat(NdjsonInput.MAX_LINE + 1) + "\n";
        assertEquals(413, assertThrows(HttpException.class, () -> input(line, line.length()).next()).status());
        assertEquals(413, assertThrows(HttpException.class, () -> input(line, 4096).next()).status());
        // a line of exactly the limit is fine
        assertEquals(NdjsonInput.MAX_LINE, input("y".repeat(NdjsonInput.MAX_LINE) + "\n", 4096).next().length());
    }

    @Test
    void aLineDecodedToNullAndAMalformedOneAreNamedByNumber() {
        NdjsonInput<String> nulls = new NdjsonInput<>(new Chunks("{}\nnull\n".getBytes(StandardCharsets.UTF_8), 3),
                (bytes, offset, length) -> length == 4 ? null : "v");
        HttpException refused = assertThrows(HttpException.class, () -> lines(nulls));
        assertEquals("NDJSON line 2 is null", refused.getMessage());

        NdjsonInput<String> bad = new NdjsonInput<>(new Chunks("{}\n\n{\n".getBytes(StandardCharsets.UTF_8), 2),
                (bytes, offset, length) -> {
                    if (length == 1) throw HttpException.badRequest("Invalid message: unexpected end");
                    return "v";
                });
        HttpException malformed = assertThrows(HttpException.class, () -> lines(bad));
        assertEquals(400, malformed.status());
        assertTrue(malformed.getMessage().startsWith("NDJSON line 3: "), malformed.getMessage());
    }

    @Test
    void aVoidInputReadsNothing() throws Exception {
        assertEquals(null, new NdjsonInput<String>(new Chunks("{}\n".getBytes(), 1), null).next());
    }

    /** A duplex that hands out {@code bytes} {@code step} at a time. */
    static final class Chunks extends Duplex {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final byte[] bytes;
        private final int step;
        private int position;
        private int limit;

        Chunks(byte[] bytes, int step) {
            this.bytes = bytes;
            this.step = step;
        }

        @Override public void write(byte[] b, int off, int len) { written.write(b, off, len); }
        @Override public void flush() {}

        @Override public int fill() {
            if (position < limit) return limit - position;
            if (limit == bytes.length) return -1;
            limit = Math.min(bytes.length, limit + step);
            return limit - position;
        }

        @Override public byte[] array() { return bytes; }
        @Override public int offset() { return position; }
        @Override public void advance(int n) { position += n; }
    }
}
