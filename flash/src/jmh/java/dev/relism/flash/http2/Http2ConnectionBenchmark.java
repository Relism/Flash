package dev.relism.flash.http2;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.FrameWriteBuffer;
import dev.relism.flash.http2.frame.Http2FrameReader;
import dev.relism.flash.http2.frame.Http2FrameWriter;
import dev.relism.flash.transport.BufferedByteSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/** Measures the allocation-free control-frame lifecycle after connection objects are prepared. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class Http2ConnectionBenchmark {
  private static final java.util.function.BooleanSupplier RUNNING = () -> false;

  private byte[] wire;
  private Http2Connection connection;
  private BufferedByteSource input;
  private Http2FrameReader reader;
  private Http2FrameWriter writer;
  private CountingSink sink;
  private ResettableInputStream stream;

  @Setup(Level.Trial)
  public void buildWire() {
    ByteWriter bytes = new ByteWriter(128);
    bytes.writeBytes("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    FrameWriteBuffer frames = new FrameWriteBuffer(bytes);
    frames.beginFrame(FrameType.SETTINGS, 0, 0);
    frames.endFrame();
    frames.beginFrame(FrameType.SETTINGS, FrameFlags.ACK, 0);
    frames.endFrame();
    frames.beginFrame(FrameType.PING, 0, 0);
    bytes.writeAscii("12345678");
    frames.endFrame();
    frames.beginFrame(FrameType.WINDOW_UPDATE, 0, 0);
    bytes.writeUInt31(1);
    frames.endFrame();
    frames.beginFrame(FrameType.GOAWAY, 0, 0);
    bytes.writeUInt31(0);
    bytes.writeUInt32(Http2ErrorCode.NO_ERROR.code());
    frames.endFrame();
    wire = new byte[bytes.length()];
    System.arraycopy(bytes.array(), 0, wire, 0, wire.length);
    setupConnection();
  }

  private void setupConnection() {
    connection = new Http2Connection();
    stream = new ResettableInputStream(wire);
    input = new BufferedByteSource(stream, null);
    reader = new Http2FrameReader(input);
    sink = new CountingSink();
    writer = new Http2FrameWriter(sink, 5_000);
  }

  @Setup(Level.Invocation)
  public void resetConnection() {
    stream.reset();
    connection.reset();
    sink.bytes = 0;
  }

  @TearDown(Level.Trial)
  public void closeWriter() {
    writer.close();
  }

  @Benchmark
  public int controlLifecycle() throws Exception {
    connection.runPrepared(input, reader, writer, RUNNING);
    return sink.bytes;
  }

  private static final class ResettableInputStream extends InputStream {
    private final byte[] bytes;
    private int position;

    private ResettableInputStream(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public void reset() {
      position = 0;
    }

    @Override
    public int read() {
      return position == bytes.length ? -1 : bytes[position++] & 0xff;
    }

    @Override
    public int read(byte[] target, int offset, int length) {
      if (position == bytes.length) return -1;
      int count = Math.min(length, bytes.length - position);
      System.arraycopy(bytes, position, target, offset, count);
      position += count;
      return count;
    }
  }

  private static final class CountingSink implements Http2FrameWriter.Sink {
    private int bytes;

    @Override
    public void write(byte[] buffer, int offset, int length) {
      bytes += length;
    }
  }
}
