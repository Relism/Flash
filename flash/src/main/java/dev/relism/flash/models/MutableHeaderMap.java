package dev.relism.flash.models;

import dev.relism.flash.bytes.ByteScan;
import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.bytes.PooledSlice;
import dev.relism.fpr.core.ByteView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.IOException;
import java.io.OutputStream;

/** Reusable owned-byte header collection for sections parsed outside the request head buffer. */
public final class MutableHeaderMap implements HeaderView {
  private final ByteWriter bytes = new ByteWriter(128);
  private final PooledSlice view = new PooledSlice();
  private final PooledSlice scanName = new PooledSlice();
  private final PooledSlice scanValue = new PooledSlice();
  private int[] fields = new int[16];
  private int count;

  public void reset() {
    bytes.reset();
    count = 0;
  }

  public void add(byte[] name, int nameOffset, int nameLength,
      byte[] value, int valueOffset, int valueLength) {
    ensure(count + 1);
    int base = count * 4;
    fields[base] = bytes.length();
    fields[base + 1] = nameLength;
    bytes.writeBytes(name, nameOffset, nameLength);
    fields[base + 2] = bytes.length();
    fields[base + 3] = valueLength;
    bytes.writeBytes(value, valueOffset, valueLength);
    count++;
  }

  public void writeLines(OutputStream output) throws IOException {
    for (int i = 0; i < count; i++) {
      int base = i * 4;
      output.write(bytes.array(), fields[base], fields[base + 1]);
      output.write(':');
      output.write(' ');
      output.write(bytes.array(), fields[base + 2], fields[base + 3]);
      output.write('\r');
      output.write('\n');
    }
  }

  void forEachStructured(ResponseSerializer.FieldConsumer consumer) {
    for (int i = 0; i < count; i++) {
      int base = i * 4;
      consumer.accept(bytes.array(), fields[base], fields[base + 1],
          bytes.array(), fields[base + 2], fields[base + 3]);
    }
  }

  @Override
  public String first(String name) {
    int index = indexOf(name, 0);
    return index < 0 ? null : value(index);
  }

  @Override
  public List<String> all(String name) {
    List<String> result = null;
    int from = 0;
    int index;
    while ((index = indexOf(name, from)) >= 0) {
      if (result == null) result = new ArrayList<>();
      result.add(value(index));
      from = index + 1;
    }
    return result == null ? List.of() : result;
  }

  @Override
  public List<String> all() {
    if (count == 0) return List.of();
    List<String> result = new ArrayList<>(count);
    for (int i = 0; i < count; i++) result.add(value(i));
    return result;
  }

  @Override
  public ByteView view(String name) {
    int index = indexOf(name, 0);
    if (index < 0) return null;
    int base = index * 4;
    view.reset(bytes.array(), fields[base + 2], fields[base + 3]);
    return view;
  }

  @Override
  public boolean valueEqualsIgnoreCase(String name, String value) {
    int index = indexOf(name, 0);
    if (index < 0) return false;
    int base = index * 4;
    return ByteScan.equalsIgnoreCaseAscii(
        bytes.array(), fields[base + 2], fields[base + 2] + fields[base + 3], value);
  }

  @Override public boolean contains(String name) { return indexOf(name, 0) >= 0; }
  @Override public int count() { return count; }

  @Override
  public void forEach(HeaderConsumer consumer) {
    for (int i = 0; i < count; i++) {
      int base = i * 4;
      scanName.reset(bytes.array(), fields[base], fields[base + 1]);
      scanValue.reset(bytes.array(), fields[base + 2], fields[base + 3]);
      consumer.accept(scanName, scanValue);
    }
  }

  private int indexOf(String name, int from) {
    for (int i = from; i < count; i++) {
      int base = i * 4;
      if (ByteScan.equalsIgnoreCaseAscii(
          bytes.array(), fields[base], fields[base] + fields[base + 1], name)) return i;
    }
    return -1;
  }

  private String value(int index) {
    int base = index * 4;
    return new String(bytes.array(), fields[base + 2], fields[base + 3], StandardCharsets.UTF_8);
  }

  private void ensure(int needed) {
    int ints = needed * 4;
    if (ints <= fields.length) return;
    fields = Arrays.copyOf(fields, Math.max(ints, fields.length * 2));
  }
}
