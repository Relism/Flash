package dev.relism.flash.http2.frame;

import dev.relism.flash.http2.Http2Limits;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one component every HTTP/2 write in this codebase passes through — connection frames and
 * stream frames alike (both are just {@link WriteIntent}s). Its entire job is serializing
 * concurrent access to one connection's socket write side as cheaply as physically possible,
 * because under multiplexing every stream on a connection shares that one socket.
 *
 * <h2>The design, three layers</h2>
 *
 * <p><b>Layer 1 — serialize outside the lock.</b> By the time {@link #write} is called, the
 * caller has already built its complete frame into a buffer it owns (see {@link WriteIntent}).
 * This writer never serializes anything; it only ever issues one bulk
 * {@code sink.write(buffer, offset, length)} call while holding the lock — never many small
 * writes, which would turn "hold the lock" into "hold the lock across a serialization pass."
 *
 * <p><b>Layer 2 — {@link ReentrantLock}, never {@code synchronized}.</b> On Java 21, a virtual
 * thread blocking inside {@code synchronized} pins its carrier platform thread; blocking on a
 * {@link ReentrantLock} unmounts it instead (JEP 491, which removes the {@code synchronized}
 * {@code ReentrantLock} is also load-bearing here for a second reason {@code synchronized}
 * cannot offer: {@link ReentrantLock#tryLock()}.
 *
 * <p><b>Layer 3 — {@code tryLock()} fast path, intrusive MPSC fallback.</b> The overwhelmingly
 * common case, even on a genuinely multiplexed connection, is exactly one stream wanting to
 * write at a given instant. {@code tryLock()} on an uncontended lock is one successful CAS; the
 * calling thread writes inline and releases — no handoff, no queue touched, no allocation, no
 * context switch. Only when {@code tryLock()} fails (genuine contention) does the intent get
 * published through {@link IntrusiveMpscQueue} (one more CAS, still zero allocation — the
 * intent itself is the queue node) for the current lock holder to drain.
 *
 * <h3>Lost-wakeup avoidance</h3>
 * The classic hazard: a producer offers its intent to the queue at the exact moment the current
 * holder has just found the queue empty and is about to unlock — the item would be stranded
 * with nobody left to drain it. This is closed by two cooperating checks, and the correctness
 * argument for why together they are sufficient is a happens-before chain through the queue's
 * {@code AtomicReference} and the lock's own acquire/release ordering (recorded in full in
 * {@code WRITER.md}, since it is exactly the kind of reasoning a future reader must be able to
 * re-derive, not just trust):
 * <pre>
 * write(intent):
 *   if tryLock() succeeds:           // 1 CAS, the fast path
 *       drive(intent)                // write intent directly, then drain the queue, then unlock
 *   else:
 *       queue.offer(intent)          // 1 CAS, zero allocation
 *       if tryLock() succeeds:       // the producer's own second chance
 *           drive(null)              // drain whatever is queued, including our own intent
 *
 * drive(firstIntentOrNull):
 *   write firstIntentOrNull if present, then poll-and-write until the queue is empty
 *   unlock()
 *   while queue.hasWork():           // the re-check-after-unlock that closes the race
 *       if !tryLock(): break         // someone else is now responsible; their own recheck covers us
 *       poll-and-write until empty
 *       unlock()
 * </pre>
 * A frame's bytes are never interleaved with another frame's bytes: every write of one intent
 * is a single {@code sink.write} call issued while holding the lock, and the lock is not
 * released between a {@code WriteIntent}'s bytes.
 *
 * <h3>Write timeout</h3>
 * A blocking write is unavoidable when the kernel send buffer is full and the peer is not
 * reading — whoever holds the lock is blocked in the syscall, holding up every other stream on
 * the connection. This is bounded by {@link Http2Limits#WRITE_TIMEOUT_MS}, enforced by a shared
 * background reaper ({@link WriteTimeoutReaper}) that interrupts the blocked thread past the
 * deadline — {@code Socket#setSoTimeout} bounds reads, not writes, so it cannot be used here.
 * connection setup), so arming/disarming the deadline for each individual write is two
 * {@code volatile} field writes, not an allocation.
 */
public final class Http2FrameWriter {

    /** What a frame's serialized bytes are ultimately written to. Kept minimal and separate
     *  from {@code java.io.OutputStream} so this class is testable without a real socket. */
    public interface Sink {
        void write(byte[] buf, int off, int len) throws IOException;
    }

    private final Sink sink;
    private final long writeTimeoutMs;
    private final ReentrantLock lock = new ReentrantLock();
    private final IntrusiveMpscQueue queue = new IntrusiveMpscQueue();

    // Set only for the duration of an in-flight sink.write() call; see WriteTimeoutReaper. A
    // single volatile write to arm, one to disarm — no timestamp is recorded here (see the
    // reaper's own Javadoc for why: a per-write System.nanoTime() call measurably missed the
    // N=1 gate's 50 ns overhead budget when this was first benchmarked, recorded in WRITER.md).
    private volatile Thread writingThread;

    public Http2FrameWriter(Sink sink) {
        this(sink, Http2Limits.WRITE_TIMEOUT_MS);
    }

    public Http2FrameWriter(Sink sink, long writeTimeoutMs) {
        this.sink = sink;
        this.writeTimeoutMs = writeTimeoutMs;
        WriteTimeoutReaper.register(this);
    }

    /**
     * Serializes and writes one frame. Returns when the bytes are in the socket buffer or
     * safely queued behind another writer. Never blocks on another stream's I/O while holding
     * the lock for longer than that stream's own single bulk write.
     *
     * <p><b>Why the fast path is gated on {@code !queue.hasWork()}, not just {@code tryLock()}</b>
     * Writing {@code intent} immediately, before anything already queued, is only
     * safe when nothing is already queued. Without the {@code hasWork()} check, this sequence
     * is possible — and violates same-producer ordering, which the stress test asserts: a
     * producer's {@code write(a)} then {@code write(b)} contends and both get queued
     * (fire-and-forget); the current holder is about to drain them but has not yet; that
     * producer's very next call, {@code write(c)}, finds the lock free (the holder released it
     * between the producer's calls) and would otherwise write {@code c} directly — landing on
     * the wire before {@code a} and {@code b}, which are still sitting in the queue. Checking
     * {@code hasWork()} first means "bypass the queue" only happens when the queue is observed
     * genuinely empty, i.e. everything previously offered — by any producer — has already been
     * written; see {@code WRITER.md} for the full argument.
     */
    public void write(WriteIntent intent) throws IOException {
        if (!queue.hasWork() && lock.tryLock()) {
            drive(intent);
        } else {
            queue.offer(intent);
            if (lock.tryLock()) {
                drive(null);
            }
        }
    }

    /** Flushes any queued intents. Called by the demux loop when it has nothing left to read —
     *  a no-op on the (overwhelmingly common) fast path where nothing is queued. */
    public void drain() throws IOException {
        if (!queue.hasWork()) return;
        if (lock.tryLock()) {
            drive(null);
        }
    }

    /** Deregisters this writer from the write-timeout reaper. Call once, when the connection
     *  closes. */
    public void close() {
        WriteTimeoutReaper.unregister(this);
    }

    private void drive(WriteIntent firstIntentOrNull) throws IOException {
        try {
            if (firstIntentOrNull != null) writeDirect(firstIntentOrNull);
            WriteIntent next;
            while ((next = queue.poll()) != null) {
                writeDirect(next);
            }
        } finally {
            lock.unlock();
        }
        // Lost-wakeup fix: re-check after unlocking, looping because this cycle itself can race
        // the same way — see the class Javadoc for the correctness argument.
        while (queue.hasWork()) {
            if (!lock.tryLock()) break;
            try {
                WriteIntent next;
                while ((next = queue.poll()) != null) {
                    writeDirect(next);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void writeDirect(WriteIntent intent) throws IOException {
        writingThread = Thread.currentThread();
        try {
            sink.write(intent.buffer(), intent.offset(), intent.length());
        } catch (IOException e) {
            if (Thread.interrupted()) {
                InterruptedIOException timeout = new InterruptedIOException(
                        "HTTP/2 write timed out after ~" + writeTimeoutMs + " ms");
                timeout.initCause(e);
                throw timeout;
            }
            throw e;
        } finally {
            writingThread = null;
            Thread.interrupted(); // clear a stray interrupt flag defensively before returning control
        }
    }

    /**
     * A single shared daemon thread scanning every registered {@link Http2FrameWriter} for a
     * blocking write that has overrun {@link Http2Limits#WRITE_TIMEOUT_MS}. One thread for the
     * whole process (like {@code DateHeader}'s refresher), not one per connection — registration
     * per-write one.
     *
     * <p>Deliberately does <em>not</em> ask each write to record a {@code System.nanoTime()}
     * {@code nanoTime()} call (plus the extra volatile field it required) costing enough to miss
     * the N=1 gate's 50 ns-over-baseline budget (recorded in {@code WRITER.md}). Instead, the
     * reaper counts <em>consecutive scans</em> a given writer has been observed still blocked
     * ({@link #writingThread} non-null); a writer blocked for more than
     * {@code WRITE_TIMEOUT_MS / SCAN_INTERVAL_MS} consecutive scans is interrupted. This trades
     * a little precision (up to one scan interval of slop — already inherent to any
     * background-reaper design) for removing all per-write timing cost.
     */
    static final class WriteTimeoutReaper {
        private static final long SCAN_INTERVAL_MS = 50;
        private static final Set<Http2FrameWriter> ACTIVE = ConcurrentHashMap.newKeySet();
        // Touched only by the single reaper thread -- no synchronization needed.
        private static final java.util.Map<Http2FrameWriter, Integer> BLOCKED_SCAN_COUNTS = new java.util.IdentityHashMap<>();

        static {
            Thread reaper = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(SCAN_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (Http2FrameWriter writer : ACTIVE) {
                        Thread t = writer.writingThread;
                        if (t == null) {
                            BLOCKED_SCAN_COUNTS.remove(writer);
                            continue;
                        }
                        int scans = BLOCKED_SCAN_COUNTS.merge(writer, 1, Integer::sum);
                        long thresholdScans = Math.max(1, writer.writeTimeoutMs / SCAN_INTERVAL_MS);
                        if (scans >= thresholdScans) {
                            t.interrupt();
                            BLOCKED_SCAN_COUNTS.remove(writer);
                        }
                    }
                }
            }, "flash-http2-write-timeout-reaper");
            reaper.setDaemon(true);
            reaper.start();
        }

        private WriteTimeoutReaper() {
        }

        static void register(Http2FrameWriter writer) {
            ACTIVE.add(writer);
        }

        static void unregister(Http2FrameWriter writer) {
            ACTIVE.remove(writer);
        }
    }
}
