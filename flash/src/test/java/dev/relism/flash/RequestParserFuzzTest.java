package dev.relism.flash;

import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.fail;

import dev.relism.flash.exceptions.MalformedRequestException;
import dev.relism.flash.transport.BufferedByteSource;
import dev.relism.flash.testing.FuzzMemory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RequestParserFuzzTest {
  private static final int CASES = 25_000;

  @Test
  void arbitraryWireBytesHaveBoundedTypedOutcomes() {
    assertTimeout(
        Duration.ofSeconds(20),
        () -> {
          byte[] input = new byte[256];
          long state = 0x9112_4854_5450_314CL;
          long baseline = FuzzMemory.snapshot();
          for (int iteration = 0; iteration < CASES; iteration++) {
            state = next(state);
            int length = (int) (state & 255);
            for (int i = 0; i < length; i++) {
              state = next(state);
              input[i] = (byte) state;
            }
            try {
              new RequestParser(512)
                  .parse(
                      new BufferedByteSource(
                          new ByteArrayInputStream(input, 0, length), null, 256));
            } catch (MalformedRequestException expected) {
              // Hostile HTTP/1 syntax is rejected with an explicit response status.
            } catch (IOException unexpected) {
              fail("in-memory input produced I/O failure at case " + iteration, unexpected);
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
