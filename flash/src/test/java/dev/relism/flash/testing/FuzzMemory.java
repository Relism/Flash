package dev.relism.flash.testing;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retained-heap assertion shared by deterministic hostile-input tests. */
public final class FuzzMemory {
  private FuzzMemory() {}

  public static long snapshot() {
    System.gc();
    System.gc();
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  public static void assertGrowthBelow(long baseline, long maximumBytes) {
    long growth = Math.max(0, snapshot() - baseline);
    assertTrue(
        growth <= maximumBytes,
        () -> "fuzz target retained " + growth + " bytes; limit is " + maximumBytes);
  }
}
