package dev.relism.flash.http2.hpack;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.bytes.PooledSlice;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class HpackEvictionRaceTest {
  @Test
  void perStreamCopySurvivesConcurrentDynamicTableEviction() throws Exception {
    HpackDecoder decoder = new HpackDecoder(64, 1024);
    HpackHeaderBlock stream = new HpackHeaderBlock(1024, 16);

    byte[] first = HexFormat.of().parseHex("40046e616d650b66697273742d76616c7565");
    decoder.decode(first, 0, first.length, stream);
    assertField(stream, 0, "name", "first-value");

    byte[] replacement =
        HexFormat.of().parseHex("400a6f746865722d6e616d650c7365636f6e642d76616c7565");
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      @SuppressWarnings("unchecked")
      Future<Void>[] readers = new Future[8];
      for (int reader = 0; reader < readers.length; reader++) {
        readers[reader] =
            executor.submit(
                () -> {
                  start.await();
                  for (int i = 0; i < 10_000; i++) {
                    assertField(stream, 0, "name", "first-value");
                  }
                  return null;
                });
      }
      start.countDown();
      for (int i = 0; i < 10_000; i++) {
        decoder.decode(replacement, 0, replacement.length, (n, v, x) -> {});
      }
      for (Future<Void> reader : readers) reader.get();
    }

    assertField(stream, 0, "name", "first-value");
  }

  @Test
  void directDynamicTableViewDemonstratesTheEvictionHazard() {
    HpackDynamicTable table = new HpackDynamicTable(64);
    PooledSlice firstName = view("name");
    PooledSlice firstValue = view("first-value");
    table.add(firstName, firstValue);

    PooledSlice borrowedName = new PooledSlice();
    PooledSlice borrowedValue = new PooledSlice();
    table.get(1, borrowedName, borrowedValue);
    String before = text(borrowedValue);

    table.add(view("other-name"), view("second-value"));
    table.add(view("other-name"), view("second-value"));
    table.add(view("other-name"), view("second-value"));
    assertNotEquals(before, text(borrowedValue));
  }

  private static void assertField(
      HpackHeaderBlock block, int index, String expectedName, String expectedValue) {
    PooledSlice name = new PooledSlice();
    PooledSlice value = new PooledSlice();
    block.get(index, name, value);
    assertEquals(expectedName, text(name));
    assertEquals(expectedValue, text(value));
  }

  private static PooledSlice view(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
    PooledSlice view = new PooledSlice();
    view.reset(bytes, 0, bytes.length);
    return view;
  }

  private static String text(PooledSlice value) {
    return new String(value.array(), value.offset(), value.length(), StandardCharsets.US_ASCII);
  }
}
