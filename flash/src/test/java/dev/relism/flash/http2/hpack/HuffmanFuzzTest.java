package dev.relism.flash.http2.hpack;

import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.fail;

import dev.relism.flash.http2.Http2Exception;
import dev.relism.flash.testing.FuzzMemory;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HuffmanFuzzTest {
  private static final int CASES = 1_000_000;

  @Test
  void arbitraryInputHasBoundedTypedOutcomes() {
    assertTimeout(
        Duration.ofSeconds(20),
        () -> {
          byte[] input = new byte[64];
          byte[] output = new byte[128];
          long state = 0x7541_4855_4646_4D4EL;
          long baseline = FuzzMemory.snapshot();
          for (int iteration = 0; iteration < CASES; iteration++) {
            state = next(state);
            int length = (int) (state & 63);
            for (int i = 0; i < length; i++) {
              state = next(state);
              input[i] = (byte) state;
            }
            try {
              Huffman.decode(input, 0, length, output, 0, output.length);
            } catch (Http2Exception expected) {
              // Malformed Huffman input has one typed protocol outcome.
            } catch (Throwable unexpected) {
              fail("unexpected failure at case " + iteration + ", length " + length, unexpected);
            }
          }
          FuzzMemory.assertGrowthBelow(baseline, 8L * 1024 * 1024);
        });
  }

  private static long next(long value) {
    value ^= value << 13;
    value ^= value >>> 7;
    return value ^ (value << 17);
  }
}
