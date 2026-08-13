package dev.relism.flash.http2.message;

import dev.relism.flash.bytes.PooledSlice;
import dev.relism.flash.http2.hpack.HpackHeaderBlock;
import dev.relism.flash.models.HeaderView;
import dev.relism.fpr.core.ByteView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Header view over stream-owned decoded HPACK storage. Pseudo-fields are excluded. */
public final class Http2HeaderMap implements HeaderView {
  private static final int VIEW_COUNT = 4;

  private final PooledSlice scanName = new PooledSlice();
  private final PooledSlice scanValue = new PooledSlice();
  private final PooledSlice[] views = new PooledSlice[VIEW_COUNT];
  private HpackHeaderBlock block;
  private PseudoHeaders pseudoHeaders;
  private int viewCursor;
  private int regularCount;

  public Http2HeaderMap() {
    for (int i = 0; i < views.length; i++) views[i] = new PooledSlice();
  }

  public void reset(HpackHeaderBlock block, PseudoHeaders pseudoHeaders) {
    this.block = block;
    this.pseudoHeaders = pseudoHeaders;
    viewCursor = 0;
    regularCount = 0;
    for (int i = 0; i < block.count(); i++) {
      block.get(i, scanName, scanValue);
      if (scanName.byteAt(0) != ':') regularCount++;
    }
  }

  public void reset(HpackHeaderBlock block) {
    reset(block, null);
  }

  @Override
  public String first(String name) {
    ByteView value = find(name, scanValue);
    if (value == null) return null;
    PooledSlice slice = (PooledSlice) value;
    return new String(slice.array(), slice.offset(), slice.length(), StandardCharsets.UTF_8);
  }

  @Override
  public List<String> all(String name) {
    List<String> result = null;
    for (int i = 0; i < block.count(); i++) {
      block.get(i, scanName, scanValue);
      if (scanName.byteAt(0) == ':' || !equalsIgnoreCase(scanName, name)) continue;
      if (result == null) result = new ArrayList<>();
      result.add(
          new String(
              scanValue.array(), scanValue.offset(), scanValue.length(), StandardCharsets.UTF_8));
    }
    if (result == null && pseudoHeaders != null
        && isAuthorityAlias(name) && pseudoHeaders.authority().array() != null) {
      return List.of(string(pseudoHeaders.authority()));
    }
    return result == null ? List.of() : result;
  }

  @Override
  public List<String> all() {
    List<String> result = new ArrayList<>(regularCount);
    for (int i = 0; i < block.count(); i++) {
      block.get(i, scanName, scanValue);
      if (scanName.byteAt(0) != ':') result.add(string(scanValue));
    }
    return result;
  }

  @Override
  public ByteView view(String name) {
    PooledSlice target = views[viewCursor++ & (views.length - 1)];
    return find(name, target);
  }

  @Override
  public boolean valueEqualsIgnoreCase(String name, String value) {
    ByteView found = find(name, scanValue);
    return found != null && equalsIgnoreCase(found, value);
  }

  @Override
  public boolean contains(String name) {
    return find(name, scanValue) != null;
  }

  @Override
  public int count() {
    return regularCount;
  }

  @Override
  public void forEach(HeaderConsumer consumer) {
    for (int i = 0; i < block.count(); i++) {
      block.get(i, scanName, scanValue);
      if (scanName.byteAt(0) != ':') consumer.accept(scanName, scanValue);
    }
  }

  private PooledSlice find(String requested, PooledSlice target) {
    for (int i = 0; i < block.count(); i++) {
      block.get(i, scanName, scanValue);
      if (scanName.byteAt(0) != ':' && equalsIgnoreCase(scanName, requested)) {
        target.reset(scanValue.array(), scanValue.offset(), scanValue.length());
        return target;
      }
    }
    if (pseudoHeaders != null
        && isAuthorityAlias(requested) && pseudoHeaders.authority().array() != null) {
      PooledSlice authority = pseudoHeaders.authority();
      target.reset(authority.array(), authority.offset(), authority.length());
      return target;
    }
    return null;
  }

  private static boolean isAuthorityAlias(String name) {
    return name.equalsIgnoreCase("host") || name.equalsIgnoreCase(":authority");
  }

  private static boolean equalsIgnoreCase(ByteView bytes, String value) {
    if (bytes.length() != value.length()) return false;
    for (int i = 0; i < bytes.length(); i++) {
      int left = bytes.byteAt(i) & 0xff;
      int right = value.charAt(i);
      if (left >= 'A' && left <= 'Z') left += 32;
      if (right >= 'A' && right <= 'Z') right += 32;
      if (left != right) return false;
    }
    return true;
  }

  private static String string(PooledSlice slice) {
    return new String(slice.array(), slice.offset(), slice.length(), StandardCharsets.UTF_8);
  }
}
