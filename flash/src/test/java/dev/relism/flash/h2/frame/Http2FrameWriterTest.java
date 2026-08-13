package dev.relism.flash.h2.frame;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Http2FrameWriterTest {

    private static final class TestIntent implements WriteIntent {
        final byte[] buf;
        WriteIntent next;
        TestIntent(byte[] buf) { this.buf = buf; }
        TestIntent(String s) { this(s.getBytes()); }
        @Override public byte[] buffer() { return buf; }
        @Override public int offset() { return 0; }
        @Override public int length() { return buf.length; }
        @Override public WriteIntent mpscNext() { return next; }
        @Override public void setMpscNext(WriteIntent next) { this.next = next; }
    }

    private static final class RecordingSink implements Http2FrameWriter.Sink {
        final List<byte[]> calls = new ArrayList<>();
        @Override
        public void write(byte[] buf, int off, int len) {
            byte[] copy = new byte[len];
            System.arraycopy(buf, off, copy, 0, len);
            calls.add(copy);
        }
    }

    @Test
    void singleWrite_deliversBytesImmediately() throws IOException {
        RecordingSink sink = new RecordingSink();
        Http2FrameWriter writer = new Http2FrameWriter(sink, 5_000);
        writer.write(new TestIntent("hello"));
        assertEquals(1, sink.calls.size());
        assertArrayEquals("hello".getBytes(), sink.calls.get(0));
        writer.close();
    }

    @Test
    void sequentialWrites_fromOneThread_preserveOrderAndAreNotSplitOrMerged() throws IOException {
        RecordingSink sink = new RecordingSink();
        Http2FrameWriter writer = new Http2FrameWriter(sink, 5_000);
        writer.write(new TestIntent("one"));
        writer.write(new TestIntent("two"));
        writer.write(new TestIntent("three"));
        assertEquals(List.of("one", "two", "three"),
                sink.calls.stream().map(String::new).toList());
        writer.close();
    }

    @Test
    void exceptionFromSink_doesNotLeaveTheLockHeld() throws IOException {
        Http2FrameWriter.Sink failingOnce = new Http2FrameWriter.Sink() {
            boolean thrown = false;
            @Override
            public void write(byte[] buf, int off, int len) throws IOException {
                if (!thrown) {
                    thrown = true;
                    throw new IOException("simulated sink failure");
                }
            }
        };
        Http2FrameWriter writer = new Http2FrameWriter(failingOnce, 5_000);

        assertThrows(IOException.class, () -> writer.write(new TestIntent("boom")));
        // If the lock were left held by the failed write, this would hang (tryLock() would
        // keep failing forever) rather than complete promptly.
        assertDoesNotThrow(() -> writer.write(new TestIntent("recovered")));
        writer.close();
    }

    @Test
    void drain_withNothingQueued_isANoOp() throws IOException {
        RecordingSink sink = new RecordingSink();
        Http2FrameWriter writer = new Http2FrameWriter(sink, 5_000);
        writer.drain();
        assertTrue(sink.calls.isEmpty());
        writer.close();
    }

    @Test
    void emptyIntent_writesZeroBytesWithoutError() throws IOException {
        RecordingSink sink = new RecordingSink();
        Http2FrameWriter writer = new Http2FrameWriter(sink, 5_000);
        writer.write(new TestIntent(new byte[0]));
        assertEquals(1, sink.calls.size());
        assertEquals(0, sink.calls.get(0).length);
        writer.close();
    }
}
