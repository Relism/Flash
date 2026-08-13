package dev.relism.flash.http2.message;

import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.fail;

import dev.relism.flash.bytes.PooledSlice;
import dev.relism.flash.http2.Http2StreamException;
import dev.relism.flash.http2.hpack.HpackHeaderBlock;
import dev.relism.flash.testing.FuzzMemory;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PseudoHeadersFuzzTest {
  private static final int CASES = 250_000;

  @Test
  void arbitraryFieldSectionsHaveBoundedTypedOutcomes() {
    assertTimeout(
        Duration.ofSeconds(20),
        () -> {
          PseudoHeaders validator = new PseudoHeaders();
          HpackHeaderBlock block = new HpackHeaderBlock();
          PooledSlice name = new PooledSlice();
          PooledSlice value = new PooledSlice();
          byte[] bytes = new byte[512];
          long state = 0x9113_5053_4555_444FL;
          long baseline = FuzzMemory.snapshot();
          for (int iteration = 0; iteration < CASES; iteration++) {
            block.reset();
            state = next(state);
            int fields = (int) (state & 15);
            int cursor = 0;
            for (int field = 0; field < fields; field++) {
              state = next(state);
              int nameLength = (int) (state & 15);
              state = next(state);
              int valueLength = (int) (state & 31);
              for (int i = 0; i < nameLength + valueLength; i++) {
                state = next(state);
                bytes[cursor + i] = (byte) state;
              }
              name.reset(bytes, cursor, nameLength);
              cursor += nameLength;
              value.reset(bytes, cursor, valueLength);
              cursor += valueLength;
              block.accept(name, value, false);
            }
            try {
              if ((iteration & 1) == 0) validator.validate(block, 1);
              else PseudoHeaders.validateTrailers(block, 1);
            } catch (Http2StreamException expected) {
              // Invalid field sections are rejected at stream scope.
            } catch (Throwable unexpected) {
              fail("unexpected failure at case " + iteration, unexpected);
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
