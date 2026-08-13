package dev.relism.flash.bytes;

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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Compares {@link ByteScan#indexOfCrLfCrLf} (SWAR) against {@link ByteScan#indexOfCrLfCrLfScalar}
 * on a realistic HTTP/1.1 request header block. It lives in this package to reach the
 * package-private scalar reference method without widening that method's visibility solely for
 * measurement.
 *
 * <p>Run: {@code mvn -Pjmh -pl flash test-compile} then {@code java -cp
 * flash/target/test-classes:flash/target/classes:$(mvn -Pjmh -pl flash dependency:build-classpath
 * -Dmdep.outputFile=/dev/stdout -q) org.openjdk.jmh.Main ByteScanBenchmark}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ByteScanBenchmark {

  /** A realistic request: request line + 7 headers + terminator, ~330 bytes. */
  private byte[] requestBuf;

  @Setup(Level.Trial)
  public void setup() {
    String req =
        "GET /users/12345?sort=desc&limit=20 HTTP/1.1\r\n"
            + "Host: api.example.com\r\n"
            + "User-Agent: Mozilla/5.0 (compatible; FlashBench/1.0)\r\n"
            + "Accept: application/json\r\n"
            + "Accept-Encoding: gzip, deflate, br\r\n"
            + "Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789\r\n"
            + "Cookie: session=xyz123abc; theme=dark; lang=en-US\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n";
    requestBuf = req.getBytes(StandardCharsets.US_ASCII);
  }

  @Benchmark
  public int headerEndScan_swar() {
    return ByteScan.indexOfCrLfCrLf(requestBuf, 0, requestBuf.length);
  }

  @Benchmark
  public int headerEndScan_scalar() {
    return ByteScan.indexOfCrLfCrLfScalar(requestBuf, 0, requestBuf.length);
  }
}
