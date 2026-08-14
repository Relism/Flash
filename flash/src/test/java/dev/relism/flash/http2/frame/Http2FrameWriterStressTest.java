package dev.relism.flash.http2.frame;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code N} producer virtual threads each write {@code M} distinguishable frames into a mock
 * sink; every byte of every frame must arrive, in valid frame-boundary order (frames from
 * different producers may interleave with each other, but a single frame's own bytes must never
 * be split by another frame's bytes — proven here because a torn frame corrupts the parser
 * below in a way the assertions catch), with no duplication and no loss, and each producer's
 * own frames must arrive in the order that producer submitted them.
 *
 * <p>This suite runs at reduced iteration counts for a fast default {@code mvn test} run. The
 * full gate verification (1000 iterations per N, plus a
 * {@code -Djdk.virtualThreadScheduler.parallelism=1} run to surface pinning/lost-wakeup bugs
 * that only appear at parallelism 1) was run manually and is recorded, with its numbers, in
 */
class Http2FrameWriterStressTest {

    private static final class TestIntent implements WriteIntent {
        final byte[] buf;
        WriteIntent next;
        TestIntent(byte[] buf) { this.buf = buf; }
        @Override public byte[] buffer() { return buf; }
        @Override public int offset() { return 0; }
        @Override public int length() { return buf.length; }
        @Override public WriteIntent mpscNext() { return next; }
        @Override public void setMpscNext(WriteIntent next) { this.next = next; }
    }

    /** Collects everything written; fails loudly if it is ever entered re-entrantly/concurrently
     *  — which would mean {@link Http2FrameWriter}'s mutual exclusion is broken. */
    private static final class RecordingSink implements Http2FrameWriter.Sink {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final AtomicBoolean writing = new AtomicBoolean(false);
        volatile boolean concurrentWriteDetected = false;

        @Override
        public void write(byte[] buf, int off, int len) {
            if (!writing.compareAndSet(false, true)) {
                concurrentWriteDetected = true;
            }
            out.write(buf, off, len);
            writing.set(false);
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    // Frame layout: [producerId:int][seq:int][marker byte, repeated payloadLen times]
    private static int payloadLenFor(int producerId, int seq) {
        return 4 + ((producerId + seq) % 20);
    }

    private static byte[] buildFrame(int producerId, int seq) {
        int payloadLen = payloadLenFor(producerId, seq);
        byte[] b = new byte[8 + payloadLen];
        writeInt(b, 0, producerId);
        writeInt(b, 4, seq);
        byte marker = (byte) (producerId ^ seq);
        for (int i = 0; i < payloadLen; i++) b[8 + i] = marker;
        return b;
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private void runStress(int producers, int framesPerProducer) throws Exception {
        RecordingSink sink = new RecordingSink();
        Http2FrameWriter writer = new Http2FrameWriter(sink, 30_000);
        try {
            try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>();
                for (int p = 0; p < producers; p++) {
                    int producerId = p;
                    futures.add(exec.submit(() -> {
                        for (int seq = 0; seq < framesPerProducer; seq++) {
                            TestIntent intent = new TestIntent(buildFrame(producerId, seq));
                            try {
                                writer.write(intent);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }));
                }
                for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
            }
            // No concurrent producers remain past this point; one drain deterministically
            // flushes anything a fire-and-forget contended write left queued.
            writer.drain();
        } finally {
            writer.close();
        }

        assertFalse(sink.concurrentWriteDetected, "writer allowed two threads to write concurrently");
        validate(sink.bytes(), producers, framesPerProducer);
    }

    private static void validate(byte[] all, int producers, int framesPerProducer) {
        int[] expectedSeq = new int[producers];
        int pos = 0;
        int frameCount = 0;
        while (pos < all.length) {
            assertTrue(pos + 8 <= all.length, "truncated frame header at byte " + pos);
            int producerId = readInt(all, pos);
            int seq = readInt(all, pos + 4);
            assertTrue(producerId >= 0 && producerId < producers, "corrupt producerId " + producerId + " at byte " + pos);
            assertEquals(expectedSeq[producerId], seq,
                    "producer " + producerId + "'s frames arrived out of order at byte " + pos);
            int payloadLen = payloadLenFor(producerId, seq);
            assertTrue(pos + 8 + payloadLen <= all.length, "truncated frame payload at byte " + pos);
            byte marker = (byte) (producerId ^ seq);
            for (int i = 0; i < payloadLen; i++) {
                assertEquals(marker, all[pos + 8 + i],
                        "corrupted or torn payload byte in frame (producer=" + producerId + ", seq=" + seq + ") at index " + i);
            }
            expectedSeq[producerId]++;
            pos += 8 + payloadLen;
            frameCount++;
        }
        assertEquals(producers * framesPerProducer, frameCount, "wrong total frame count");
        for (int p = 0; p < producers; p++) {
            assertEquals(framesPerProducer, expectedSeq[p], "producer " + p + " is missing frames");
        }
    }

    @Test void stress_n1() throws Exception { runStress(1, 500); }
    @Test void stress_n2() throws Exception { runStress(2, 300); }
    @Test void stress_n8() throws Exception { runStress(8, 150); }
    @Test void stress_n64() throws Exception { runStress(64, 40); }
    @Test void stress_n256() throws Exception { runStress(256, 15); }
}
