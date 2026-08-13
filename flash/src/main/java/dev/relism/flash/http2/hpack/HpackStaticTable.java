package dev.relism.flash.http2.hpack;

import dev.relism.fpr.core.ByteView;
import java.nio.charset.StandardCharsets;

/** The immutable 61-entry HPACK static table defined by RFC 7541 Appendix A. */
public final class HpackStaticTable {
  public static final int LENGTH = 61;

  private static final byte[][] NAMES = new byte[LENGTH + 1][];
  private static final byte[][] VALUES = new byte[LENGTH + 1][];
  private static final int[] NAME_INDEX = new int[128];
  private static final int[] PAIR_INDEX = new int[128];

  static {
    add(1, ":authority", "");
    add(2, ":method", "GET");
    add(3, ":method", "POST");
    add(4, ":path", "/");
    add(5, ":path", "/index.html");
    add(6, ":scheme", "http");
    add(7, ":scheme", "https");
    add(8, ":status", "200");
    add(9, ":status", "204");
    add(10, ":status", "206");
    add(11, ":status", "304");
    add(12, ":status", "400");
    add(13, ":status", "404");
    add(14, ":status", "500");
    add(15, "accept-charset", "");
    add(16, "accept-encoding", "gzip, deflate");
    add(17, "accept-language", "");
    add(18, "accept-ranges", "");
    add(19, "accept", "");
    add(20, "access-control-allow-origin", "");
    add(21, "age", "");
    add(22, "allow", "");
    add(23, "authorization", "");
    add(24, "cache-control", "");
    add(25, "content-disposition", "");
    add(26, "content-encoding", "");
    add(27, "content-language", "");
    add(28, "content-length", "");
    add(29, "content-location", "");
    add(30, "content-range", "");
    add(31, "content-type", "");
    add(32, "cookie", "");
    add(33, "date", "");
    add(34, "etag", "");
    add(35, "expect", "");
    add(36, "expires", "");
    add(37, "from", "");
    add(38, "host", "");
    add(39, "if-match", "");
    add(40, "if-modified-since", "");
    add(41, "if-none-match", "");
    add(42, "if-range", "");
    add(43, "if-unmodified-since", "");
    add(44, "last-modified", "");
    add(45, "link", "");
    add(46, "location", "");
    add(47, "max-forwards", "");
    add(48, "proxy-authenticate", "");
    add(49, "proxy-authorization", "");
    add(50, "range", "");
    add(51, "referer", "");
    add(52, "refresh", "");
    add(53, "retry-after", "");
    add(54, "server", "");
    add(55, "set-cookie", "");
    add(56, "strict-transport-security", "");
    add(57, "transfer-encoding", "");
    add(58, "user-agent", "");
    add(59, "vary", "");
    add(60, "via", "");
    add(61, "www-authenticate", "");

    for (int i = LENGTH; i >= 1; i--) {
      put(NAME_INDEX, hash(NAMES[i]), i, false);
      put(PAIR_INDEX, hashPair(NAMES[i], VALUES[i]), i, true);
    }
  }

  private HpackStaticTable() {}

  private static void add(int index, String name, String value) {
    NAMES[index] = name.getBytes(StandardCharsets.US_ASCII);
    VALUES[index] = value.getBytes(StandardCharsets.US_ASCII);
  }

  public static byte[] name(int index) {
    checkIndex(index);
    return NAMES[index];
  }

  public static byte[] value(int index) {
    checkIndex(index);
    return VALUES[index];
  }

  public static int findName(ByteView name) {
    int slot = hash(name) & (NAME_INDEX.length - 1);
    while (NAME_INDEX[slot] != 0) {
      int index = NAME_INDEX[slot];
      if (equals(name, NAMES[index])) return index;
      slot = (slot + 1) & (NAME_INDEX.length - 1);
    }
    return 0;
  }

  public static int findPair(ByteView name, ByteView value) {
    int slot = hashPair(name, value) & (PAIR_INDEX.length - 1);
    while (PAIR_INDEX[slot] != 0) {
      int index = PAIR_INDEX[slot];
      if (equals(name, NAMES[index]) && equals(value, VALUES[index])) return index;
      slot = (slot + 1) & (PAIR_INDEX.length - 1);
    }
    return 0;
  }

  private static void put(int[] table, int hash, int index, boolean pair) {
    int slot = hash & (table.length - 1);
    while (table[slot] != 0
        && !(equals(NAMES[index], NAMES[table[slot]])
            && (!pair || equals(VALUES[index], VALUES[table[slot]])))) {
      slot = (slot + 1) & (table.length - 1);
    }
    table[slot] = index;
  }

  private static int hash(ByteView value) {
    int hash = 0x811C9DC5;
    for (int i = 0; i < value.length(); i++) hash = (hash ^ (value.byteAt(i) & 0xff)) * 0x01000193;
    return hash;
  }

  private static int hash(byte[] value) {
    int hash = 0x811C9DC5;
    for (byte b : value) hash = (hash ^ (b & 0xff)) * 0x01000193;
    return hash;
  }

  private static int hashPair(ByteView name, ByteView value) {
    int hash = hash(name);
    for (int i = 0; i < value.length(); i++) hash = (hash ^ (value.byteAt(i) & 0xff)) * 0x01000193;
    return hash;
  }

  private static int hashPair(byte[] name, byte[] value) {
    int hash = hash(name);
    for (byte b : value) hash = (hash ^ (b & 0xff)) * 0x01000193;
    return hash;
  }

  private static boolean equals(ByteView view, byte[] bytes) {
    if (view.length() != bytes.length) return false;
    for (int i = 0; i < bytes.length; i++) if (view.byteAt(i) != bytes[i]) return false;
    return true;
  }

  private static boolean equals(byte[] left, byte[] right) {
    return java.util.Arrays.equals(left, right);
  }

  private static void checkIndex(int index) {
    if (index < 1 || index > LENGTH)
      throw new IndexOutOfBoundsException("HPACK static index " + index);
  }
}
