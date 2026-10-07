package dev.relism.flash.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.relism.flash.http.Duplex;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SseStreamTest {

    @Test
    void writesMultilineDataAndStandardMetadata() throws Exception {
        MemoryDuplex duplex = new MemoryDuplex();
        SseStream stream = new SseStream(duplex);

        stream.send(SseEvent.of("one\r\ntwo").event("update").id("7").retry(Duration.ofSeconds(2)));

        assertEquals("id: 7\nevent: update\nretry: 2000\ndata: one\ndata: two\n\n",
                duplex.out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void closeIsIdempotentAndRejectsLaterEvents() {
        SseStream stream = new SseStream(new MemoryDuplex());
        stream.close();
        stream.close();
        assertFalse(stream.isOpen());
        assertThrows(IOException.class, () -> stream.send("late"));
    }

    @Test
    void keepAliveWritesACommentAndSurfacesDisconnect() throws Exception {
        MemoryDuplex duplex = new MemoryDuplex();
        new SseStream(duplex).keepAlive();
        assertEquals(":\n", duplex.out.toString(StandardCharsets.US_ASCII));

        SseStream disconnected = new SseStream(new MemoryDuplex() {
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                throw new IOException("gone");
            }
        });
        assertThrows(IOException.class, disconnected::keepAlive);
    }

    private static class MemoryDuplex extends Duplex {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); }
        @Override public void flush() {}
        @Override public int fill() { return -1; }
        @Override public byte[] array() { return new byte[0]; }
        @Override public int offset() { return 0; }
        @Override public void advance(int count) {}
    }
}
