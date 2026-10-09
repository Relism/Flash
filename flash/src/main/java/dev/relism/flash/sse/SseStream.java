package dev.relism.flash.sse;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http.Duplex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Output-only SSE stream. Sending and closing are safe from any thread. */
public final class SseStream implements AutoCloseable {
    private static final byte[] DATA = "data: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EVENT = "event: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ID = "id: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RETRY = "retry: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEEP_ALIVE = ":\n".getBytes(StandardCharsets.US_ASCII);
    private static final long KEEP_ALIVE_NANOS = TimeUnit.SECONDS.toNanos(15);

    private final Duplex duplex;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ended = lock.newCondition();
    private final ByteWriter out = new ByteWriter(256);
    private boolean closed;

    SseStream(Duplex duplex) {
        this.duplex = duplex;
    }

    public void send(String event) throws IOException {
        byte[] bytes = event.getBytes(StandardCharsets.UTF_8);
        send(bytes, 0, bytes.length);
    }

    public void send(SseEvent event) throws IOException {
        lock.lock();
        try {
            if (closed) throw new IOException("the event stream is closed");
            out.reset();
            field(ID, event.id());
            field(EVENT, event.event());
            if (event.retryMillis() >= 0) {
                out.writeBytes(RETRY);
                out.writeAscii(Long.toString(event.retryMillis()));
                out.writeByte((byte) '\n');
            }
            byte[] data = event.data().getBytes(StandardCharsets.UTF_8);
            data(data, 0, data.length);
            duplex.write(out.array(), 0, out.length());
            duplex.flush();
        } finally {
            lock.unlock();
        }
    }

    public void send(byte[] event, int offset, int length) throws IOException {
        lock.lock();
        try {
            if (closed) throw new IOException("the event stream is closed");
            out.reset();
            data(event, offset, length);
            duplex.write(out.array(), 0, out.length());
            duplex.flush();
        } finally {
            lock.unlock();
        }
    }

    /** Keeps an asynchronously-fed stream open and detects disconnects on a 15-second heartbeat. */
    public void await() throws IOException {
        lock.lock();
        try {
            if (!closed) duplex.flush(); // the client learns the stream is open before the first event
            while (!closed) {
                try {
                    if (ended.awaitNanos(KEEP_ALIVE_NANOS) > 0) continue;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                keepAlive();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            ended.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isOpen() {
        lock.lock();
        try {
            return !closed;
        } finally {
            lock.unlock();
        }
    }

    void keepAlive() throws IOException {
        duplex.write(KEEP_ALIVE);
        duplex.flush();
    }

    private void data(byte[] bytes, int offset, int length) {
        out.writeBytes(DATA);
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            byte value = bytes[i];
            if (value == '\r' || value == '\n') {
                if (value == '\r' && i + 1 < end && bytes[i + 1] == '\n') i++;
                out.writeByte((byte) '\n');
                out.writeBytes(DATA);
            } else {
                out.writeByte(value);
            }
        }
        out.writeByte((byte) '\n');
        out.writeByte((byte) '\n');
    }

    private void field(byte[] name, String value) {
        if (value == null) return;
        out.writeBytes(name);
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.writeByte((byte) '\n');
    }

}
