package dev.relism.flash.http;

import dev.relism.flash.models.HeaderView;
import dev.relism.fpr.core.ByteView;

/** Shared proxy policy for fields that must not cross an HTTP connection boundary. */
public final class HopByHopHeaders {
  public enum Protocol {
    HTTP_1_1,
    HTTP_2
  }

  private HopByHopHeaders() {}

  /** Returns whether a field may be copied to a new downstream connection. */
  public static boolean shouldForward(
      HeaderView source,
      ByteView name,
      ByteView value,
      Protocol sourceProtocol,
      Protocol targetProtocol) {
    if (name.length() == 0 || name.byteAt(0) == ':') return false;
    if (is(name, "connection")
        || is(name, "keep-alive")
        || is(name, "proxy-connection")
        || is(name, "proxy-authenticate")
        || is(name, "proxy-authorization")
        || is(name, "trailer")
        || is(name, "transfer-encoding")
        || is(name, "upgrade")) {
      return false;
    }
    if (isConnectionListed(source, name)) return false;
    if (is(name, "te")) {
      return targetProtocol == Protocol.HTTP_2 && isTrimmed(value, "trailers");
    }
    return true;
  }

  private static boolean isConnectionListed(HeaderView source, ByteView fieldName) {
    for (String value : source.all("connection")) {
      int start = 0;
      while (start < value.length()) {
        int comma = value.indexOf(',', start);
        int end = comma < 0 ? value.length() : comma;
        while (start < end && isWhitespace(value.charAt(start))) start++;
        while (end > start && isWhitespace(value.charAt(end - 1))) end--;
        if (equalsAsciiIgnoreCase(fieldName, value, start, end)) return true;
        start = comma < 0 ? value.length() : comma + 1;
      }
    }
    return false;
  }

  private static boolean is(ByteView bytes, String expected) {
    return equalsAsciiIgnoreCase(bytes, expected, 0, expected.length());
  }

  private static boolean isTrimmed(ByteView bytes, String expected) {
    int start = 0;
    int end = bytes.length();
    while (start < end && isWhitespace((char) bytes.byteAt(start))) start++;
    while (end > start && isWhitespace((char) bytes.byteAt(end - 1))) end--;
    if (end - start != expected.length()) return false;
    for (int i = 0; i < expected.length(); i++) {
      if (lower(bytes.byteAt(start + i) & 0xff) != lower(expected.charAt(i))) return false;
    }
    return true;
  }

  private static boolean equalsAsciiIgnoreCase(
      ByteView bytes, String expected, int expectedStart, int expectedEnd) {
    if (bytes.length() != expectedEnd - expectedStart) return false;
    for (int i = 0; i < bytes.length(); i++) {
      if (lower(bytes.byteAt(i) & 0xff) != lower(expected.charAt(expectedStart + i))) return false;
    }
    return true;
  }

  private static int lower(int value) {
    return value >= 'A' && value <= 'Z' ? value + ('a' - 'A') : value;
  }

  private static boolean isWhitespace(char value) {
    return value == ' ' || value == '\t';
  }
}
