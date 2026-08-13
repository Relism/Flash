package dev.relism.flash.h2.frame;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Phase 3's go/no-go benchmark (flash/docs/http2/IMPLEMENTATION-PLAN.md). Compares three writer
 * designs at N ∈ {1, 2, 4, 8, 16, 64} concurrent <b>virtual-thread</b> writers:
 *
 * <ul>
 *   <li>{@code trylock_mpsc} — the design that ships as {@link Http2FrameWriter}: {@code tryLock()}
 *       fast path, intrusive MPSC fallback.</li>
 *   <li>{@code plain_lock} — every write blocks on {@code ReentrantLock#lock()}, unconditionally.</li>
 *   <li>{@code dedicated_thread} — every write hands off to a single dedicated platform thread
 *       via the same {@link IntrusiveMpscQueue}, parked/unparked (never a busy poll).</li>
 * </ul>
 *
 * <h3>Why this benchmark drives its own concurrency instead of JMH's {@code @Threads}</h3>
 * {@code @Threads} requires a compile-time constant, not a {@code @Param}-swept value, and JMH's
 * thread pool is platform threads, not virtual threads — the exact scheduling behaviour under
 * test. Each {@code @Benchmark} invocation therefore spawns {@link #threads} virtual threads
 * itself, has them race a fixed burst of writes to a counting no-op sink, and reports the
 * burst's wall-clock rate; JMH still owns fork/warmup/measurement-iteration control and (via
 * {@code -prof gc}) the zero-allocation verification.
 *
 * <h3>Why {@code runBurst} waits on a write counter, not just thread completion</h3>
 * {@code write()} does not mean "already on the wire" for every design: the shipped design's
 * contended path, and the dedicated-thread design's handoff, can both return once the frame is
 * merely *queued*. Timing only "how long until every producer's {@code write()} call returned"
 * would therefore measure submission speed, not completion speed, and would flatter exactly the
 * designs that most aggressively defer work — the opposite of a fair comparison. Every harness
 * here writes through {@link CountingSink} and {@link #runBurst} waits for its counter to reach
 * the expected total before returning, so the timed interval always covers real completion.
 *
 * <p>Per-write latency percentiles are computed by hand from {@code System.nanoTime()} samples
 * collected during the burst (JMH's own {@code Mode.SampleTime} does not fit a
 * custom-concurrency benchmark method) and printed once per (design, threads) combination — see
 * {@code WRITER.md} for the recorded results and the gate decision.
 *
 * <p>Run: {@code mvn -Pjmh -pl flash test-compile} then
 * {@code java -cp flash/target/test-classes:flash/target/classes:$(mvn -Pjmh -pl flash dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q)
 * org.openjdk.jmh.Main FrameWriterBenchmark -prof gc}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class FrameWriterBenchmark {

    private static final int FRAMES_PER_THREAD = 4000;
    private static final int FRAME_SIZE = 512;

    @Param({"trylock_mpsc", "plain_lock", "dedicated_thread", "raw_unsynchronized"})
    public String design;

    @Param({"1", "2", "4", "8", "16", "64"})
    public int threads;

    private DesignHarness harness;
    private byte[] payload;

    @Setup(Level.Trial)
    public void setup() {
        payload = new byte[FRAME_SIZE];
        harness = switch (design) {
            case "trylock_mpsc" -> new TryLockMpscHarness();
            case "plain_lock" -> new PlainLockHarness();
            case "dedicated_thread" -> new DedicatedThreadHarness();
            case "raw_unsynchronized" -> new RawUnsynchronizedHarness();
            default -> throw new IllegalStateException("unknown design: " + design);
        };
    }

    @TearDown(Level.Trial)
    public void teardown() {
        harness.shutdown();
    }

    /**
     * One "operation" here is a full burst: {@link #threads} virtual threads each writing
     * {@link #FRAMES_PER_THREAD} frames. Reported ops/sec must be multiplied by
     * {@code threads * FRAMES_PER_THREAD} to get frames/sec — done during result analysis, not
     * via {@code @OperationsPerInvocation} (which requires a compile-time constant and cannot
     * vary with the {@code threads} @Param).
     */
    @Benchmark
    public void burst() throws Exception {
        harness.runBurst(threads, FRAMES_PER_THREAD, payload);
    }

    // ── Harness abstraction and the three designs under comparison ─────────────

    private interface DesignHarness {
        void runBurst(int threads, int framesPerThread, byte[] payload) throws Exception;
        void shutdown();
    }

    /** Discards everything (isolating the writer designs from real socket variance) but counts
     *  every completed write, so callers can wait for true completion rather than mere
     *  submission — see the class Javadoc. */
    private static final class CountingSink implements Http2FrameWriter.Sink {
        final AtomicLong count = new AtomicLong();
        @Override
        public void write(byte[] buf, int off, int len) {
            count.incrementAndGet();
        }
    }

    private static final class BenchIntent implements WriteIntent {
        final byte[] buf;
        WriteIntent next;
        BenchIntent(byte[] buf) { this.buf = buf; }
        @Override public byte[] buffer() { return buf; }
        @Override public int offset() { return 0; }
        @Override public int length() { return buf.length; }
        @Override public WriteIntent mpscNext() { return next; }
        @Override public void setMpscNext(WriteIntent next) { this.next = next; }
    }

    private interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }

    /**
     * Spawns {@code threadCount} virtual threads, has each write {@code framesPerThread} fresh
     * {@link BenchIntent}s (one per write — matches production usage, where a stream's scratch
     * buffer holds exactly one in-flight frame at a time), records per-write latency samples,
     * then blocks until {@code sink}'s counter reflects every one of them actually written.
     */
    private static void race(int threadCount, int framesPerThread, CountingSink sink,
                              ThrowingConsumer<WriteIntent> write) throws Exception {
        long target = sink.count.get() + (long) threadCount * framesPerThread;
        byte[] payload = new byte[FRAME_SIZE];
        long[][] samplesByThread = new long[threadCount][framesPerThread];
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?>[] futures = new Future<?>[threadCount];
            for (int t = 0; t < threadCount; t++) {
                int idx = t;
                futures[t] = exec.submit(() -> {
                    long[] samples = samplesByThread[idx];
                    for (int i = 0; i < framesPerThread; i++) {
                        BenchIntent intent = new BenchIntent(payload);
                        long start = System.nanoTime();
                        try {
                            write.accept(intent);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        samples[i] = System.nanoTime() - start;
                    }
                });
            }
            for (Future<?> f : futures) f.get();
        }
        while (sink.count.get() < target) {
            Thread.onSpinWait();
        }
        LatencyReport.recordAndMaybePrint(samplesByThread);
    }

    /** Prints p50/p99/p999 to stdout once per thread-count actually exercised, from the first
     *  burst observed for it — cheap, and avoids flooding the JMH log with one line per
     *  measurement iteration. */
    private static final class LatencyReport {
        private static final Set<String> PRINTED = ConcurrentHashMap.newKeySet();

        static void recordAndMaybePrint(long[][] samplesByThread) {
            String key = samplesByThread.length + "t";
            if (!PRINTED.add(key)) return;

            int total = 0;
            for (long[] s : samplesByThread) total += s.length;
            long[] all = new long[total];
            int pos = 0;
            for (long[] s : samplesByThread) {
                System.arraycopy(s, 0, all, pos, s.length);
                pos += s.length;
            }
            Arrays.sort(all);
            long p50 = all[(int) (all.length * 0.50)];
            long p99 = all[(int) (all.length * 0.99)];
            long p999 = all[Math.min(all.length - 1, (int) (all.length * 0.999))];
            System.out.printf("[latency threads=%d] p50=%.1fus p99=%.1fus p999=%.1fus (n=%d)%n",
                    samplesByThread.length, p50 / 1000.0, p99 / 1000.0, p999 / 1000.0, all.length);
        }
    }

    // ── Baseline: no synchronization at all ─────────────────────────────────────
    // Not a candidate design (concurrent writers would tear each other's frames) — exists
    // purely to establish "what a write costs with zero coordination overhead" for the N=1
    // gate criterion ("per-frame overhead versus a raw unsynchronized write is within 50 ns").
    // At N=1 there genuinely is no concurrent writer, so the missing safety is moot there.

    private static final class RawUnsynchronizedHarness implements DesignHarness {
        private final CountingSink sink = new CountingSink();

        @Override
        public void runBurst(int threads, int framesPerThread, byte[] payload) throws Exception {
            race(threads, framesPerThread, sink,
                    intent -> sink.write(intent.buffer(), intent.offset(), intent.length()));
        }

        @Override public void shutdown() { }
    }

    // ── Design (a): plain lock ──────────────────────────────────────────────────

    private static final class PlainLockHarness implements DesignHarness {
        private final CountingSink sink = new CountingSink();
        private final ReentrantLock lock = new ReentrantLock();

        @Override
        public void runBurst(int threads, int framesPerThread, byte[] payload) throws Exception {
            race(threads, framesPerThread, sink, intent -> {
                lock.lock();
                try {
                    sink.write(intent.buffer(), intent.offset(), intent.length());
                } finally {
                    lock.unlock();
                }
            });
        }

        @Override public void shutdown() { }
    }

    // ── Design (b): tryLock + intrusive MPSC — the shipped design ──────────────

    private static final class TryLockMpscHarness implements DesignHarness {
        private final CountingSink sink = new CountingSink();
        private final Http2FrameWriter writer = new Http2FrameWriter(sink, 30_000);

        @Override
        public void runBurst(int threads, int framesPerThread, byte[] payload) throws Exception {
            race(threads, framesPerThread, sink, writer::write);
        }

        @Override public void shutdown() { writer.close(); }
    }

    // ── Design (c): always hand off to one dedicated writer thread ─────────────

    private static final class DedicatedThreadHarness implements DesignHarness {
        private final CountingSink sink = new CountingSink();
        private final IntrusiveMpscQueue queue = new IntrusiveMpscQueue();
        private final Thread writerThread;
        private volatile boolean running = true;

        DedicatedThreadHarness() {
            this.writerThread = Thread.ofPlatform().name("bench-dedicated-writer").start(this::loop);
        }

        private void loop() {
            while (running) {
                WriteIntent intent = queue.poll();
                if (intent == null) {
                    LockSupport.park();
                    continue;
                }
                sink.write(intent.buffer(), intent.offset(), intent.length());
            }
        }

        @Override
        public void runBurst(int threads, int framesPerThread, byte[] payload) throws Exception {
            race(threads, framesPerThread, sink, intent -> {
                queue.offer(intent);
                LockSupport.unpark(writerThread);
            });
        }

        @Override
        public void shutdown() {
            running = false;
            writerThread.interrupt();
        }
    }
}
