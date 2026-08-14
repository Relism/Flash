package dev.relism.flash.models;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A header name/value pair pre-encoded once (typically at boot, as a {@code static final} constant)
 * and reused across many responses via {@link Response#header(PreEncodedHeader)}.
 *
 * <p>Unlike {@link Response#header(byte[])}, which accepts an opaque HTTP/1 field line, this class
 * preserves the name/value boundary. HTTP/1 can render it as a line and HTTP/2 can encode it with
 * HPACK, so one constant works on both protocols.
 */
public final class PreEncodedHeader {
  private final byte[] nameBytes;
  private final byte[] valueBytes;

  public PreEncodedHeader(String name, String value) {
    this.nameBytes = name.getBytes(StandardCharsets.US_ASCII);
    this.valueBytes = value.getBytes(StandardCharsets.US_ASCII);
  }

  /** Header-name ASCII bytes. The returned array is immutable by contract. */
  byte[] nameBytes() {
    return nameBytes;
  }

  /** Header-value ASCII bytes. The returned array is immutable by contract. */
  byte[] valueBytes() {
    return valueBytes;
  }

  @Override
  public String toString() {
    return new String(nameBytes, StandardCharsets.US_ASCII)
        + ": "
        + new String(valueBytes, StandardCharsets.US_ASCII);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof PreEncodedHeader other)) return false;
    return Arrays.equals(nameBytes, other.nameBytes) && Arrays.equals(valueBytes, other.valueBytes);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(nameBytes) + Arrays.hashCode(valueBytes);
  }
}
