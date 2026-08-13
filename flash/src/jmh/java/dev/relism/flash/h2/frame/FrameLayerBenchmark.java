package dev.relism.flash.h2.frame;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.transport.BufferedByteSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Phase 5's zero-alloc contract: "Reading, validating and discarding a frame: 0 B/op ... Writing
 * a frame header: 0 B/op." Measured with {@code -prof gc}, not merely asserted — see
 * {@code DECISIONS.md}, {@code DEC-21}, for the recorded numbers.
 *
 * <p>Uses the same hand-rolled repeating {@link InputStream} technique
 * {@code RequestPipelineBenchmark} (Phase 4) established: one {@link BufferedByteSource}/
 * {@link Http2FrameReader} pair created once per trial and reused across every invocation,
 * matching how a real connection's demux loop owns exactly one of each for its whole lifetime,
 * rather than paying for harness-side (re)construction inside the timed path.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class FrameLayerBenchmark {

    private static final class RepeatingByteStream extends InputStream {
        private final byte[] template;
        private int pos;

        RepeatingByteStream(byte[] template) {
            this.template = template;
        }

        @Override
        public int read() {
            byte b = template[pos];
            pos = (pos + 1) % template.length;
            return b & 0xFF;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            for (int i = 0; i < len; i++) {
                dst[off + i] = template[pos];
                pos = (pos + 1) % template.length;
            }
            return len;
        }
    }

    // ── Read + validate ──────────────────────────────────────────────────────

    private Http2FrameReader reader;

    @Setup(Level.Trial)
    public void setupReader() {
        FrameWriteBuffer out = new FrameWriteBuffer(new ByteWriter(64));
        out.beginFrame(FrameType.HEADERS, FrameFlags.END_HEADERS, 1);
        byte[] payload = new byte[48];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        out.writer().writeBytes(payload);
        out.endFrame();
        byte[] template = new byte[out.writer().length()];
        System.arraycopy(out.writer().array(), 0, template, 0, template.length);

        BufferedByteSource src = new BufferedByteSource(new RepeatingByteStream(template), null);
        reader = new Http2FrameReader(src);
    }

    @Benchmark
    public int readValidateAndDiscard() throws IOException {
        FrameHeader header = reader.readFrame();
        FrameValidator.validate(header, false);
        int checksum = header.buffer()[header.payloadOffset()];
        reader.consumeFrame();
        return checksum;
    }

    // ── Write ────────────────────────────────────────────────────────────────

    private FrameWriteBuffer writeBuffer;
    private byte[] writePayload;

    @Setup(Level.Trial)
    public void setupWriter() {
        writeBuffer = new FrameWriteBuffer(new ByteWriter(64));
        writePayload = new byte[48];
        for (int i = 0; i < writePayload.length; i++) writePayload[i] = (byte) i;
    }

    @Benchmark
    public int writeFrame() {
        writeBuffer.writer().reset();
        writeBuffer.beginFrame(FrameType.HEADERS, FrameFlags.END_HEADERS, 1);
        writeBuffer.writer().writeBytes(writePayload);
        writeBuffer.endFrame();
        return writeBuffer.writer().length();
    }
}
