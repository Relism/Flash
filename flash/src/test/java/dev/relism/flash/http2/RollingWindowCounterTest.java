package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RollingWindowCounterTest {
  @Test
  void retainsOnlyCurrentAndImmediatelyPreviousHalfWindow() {
    RollingWindowCounter counter = new RollingWindowCounter(1_000);
    assertFalse(counter.incrementExceeded(2, 500_000_000L));
    assertFalse(counter.incrementExceeded(2, 999_000_000L));
    assertTrue(counter.incrementExceeded(2, 1_000_000_000L));
    assertFalse(counter.incrementExceeded(2, 1_500_000_000L));
  }

  @Test
  void longIdleGapClearsBothBuckets() {
    RollingWindowCounter counter = new RollingWindowCounter(1_000);
    assertFalse(counter.incrementExceeded(1, 500_000_000L));
    assertFalse(counter.incrementExceeded(1, 2_000_000_000L));
  }
}
