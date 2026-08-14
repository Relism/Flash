package dev.relism.flash.http2.hpack;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import dev.relism.flash.http2.Http2Exception;
import dev.relism.flash.testing.FuzzMemory;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HpackDecoderFuzzTest {
  private static final int CASES = 10_000_000;
  private static final HeaderSink DISCARD = (name, value, never) -> {};

  @Test
  void tenMillionRandomBlocksOnlyProduceTypedRejections() {
    assertTimeout(Duration.ofSeconds(20), this::runFuzzCases);
  }

  private void runFuzzCases() {
    HpackDecoder decoder = new HpackDecoder(256, 1024);
    byte[] input = new byte[64];
    long state = 0x7541_9113_C0DEL;
    long baseline = FuzzMemory.snapshot();
    for (int iteration = 0; iteration < CASES; iteration++) {
      state = next(state);
      int length = (int) state & 63;
      for (int i = 0; i < length; i++) {
        state = next(state);
        input[i] = (byte) state;
      }
      try {
        decoder.decode(input, 0, length, DISCARD);
      } catch (Http2Exception | HeaderListSizeException expected) {
        // Typed protocol rejection.
      } catch (Throwable unexpected) {
        fail("unexpected failure at iteration " + iteration + ", length " + length, unexpected);
      }
    }
    FuzzMemory.assertGrowthBelow(baseline, 8L * 1024 * 1024);
  }

  private static long next(long value) {
    value ^= value << 13;
    value ^= value >>> 7;
    return value ^ (value << 17);
  }
}
