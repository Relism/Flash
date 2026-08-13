package dev.relism.flash.http2;

/**
 * The peer's HTTP/2 SETTINGS state. A received payload is validated completely before any value is
 * applied, so a malformed parameter cannot leave a partially-updated connection.
 */
public final class Http2Settings {
  public static final int HEADER_TABLE_SIZE = 0x1;
  public static final int ENABLE_PUSH = 0x2;
  public static final int MAX_CONCURRENT_STREAMS = 0x3;
  public static final int INITIAL_WINDOW_SIZE = 0x4;
  public static final int MAX_FRAME_SIZE = 0x5;
  public static final int MAX_HEADER_LIST_SIZE = 0x6;

  public static final int DEFAULT_HEADER_TABLE_SIZE = 4_096;
  public static final int DEFAULT_INITIAL_WINDOW_SIZE = 65_535;
  public static final int DEFAULT_MAX_FRAME_SIZE = 16_384;

  /**
   * Applies an INITIAL_WINDOW_SIZE delta to every open stream. Implementations must validate all
   * resulting windows before changing any of them; negative results are valid, while a result above
   * {@link Integer#MAX_VALUE} is a connection FLOW_CONTROL_ERROR.
   */
  @FunctionalInterface
  public interface StreamWindowUpdater {
    void applyInitialWindowDelta(int delta);
  }

  private int headerTableSize = DEFAULT_HEADER_TABLE_SIZE;
  private boolean pushEnabled = true;
  private long maxConcurrentStreams = 0xFFFF_FFFFL;
  private int initialWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
  private int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
  private long maxHeaderListSize = 0xFFFF_FFFFL;

  /** Validates and applies one SETTINGS payload. Unknown identifiers are ignored. */
  public void apply(byte[] payload, int off, int len, StreamWindowUpdater streamWindows) {
    if (len % 6 != 0) throw Http2Exception.FRAME_SIZE_ERROR;
    int entries = len / 6;
    if (entries > Http2Limits.MAX_SETTINGS_ENTRIES_PER_FRAME) {
      throw Http2Exception.of(
          Http2ErrorCode.ENHANCE_YOUR_CALM, "too many SETTINGS entries: " + entries);
    }
    checkRange(payload, off, len);

    int nextHeaderTableSize = headerTableSize;
    boolean nextPushEnabled = pushEnabled;
    long nextMaxConcurrentStreams = maxConcurrentStreams;
    int nextInitialWindowSize = initialWindowSize;
    int nextMaxFrameSize = maxFrameSize;
    long nextMaxHeaderListSize = maxHeaderListSize;
    for (int pos = off; pos < off + len; pos += 6) {
      int id = readUInt16(payload, pos);
      long value = readUInt32(payload, pos + 2);
      validate(id, value);
      switch (id) {
        case HEADER_TABLE_SIZE ->
            nextHeaderTableSize = (int) Math.min(value, Http2Limits.HPACK_DYNAMIC_TABLE_SIZE_LOCAL);
        case ENABLE_PUSH -> nextPushEnabled = value == 1;
        case MAX_CONCURRENT_STREAMS -> nextMaxConcurrentStreams = value;
        case INITIAL_WINDOW_SIZE -> nextInitialWindowSize = (int) value;
        case MAX_FRAME_SIZE -> nextMaxFrameSize = (int) value;
        case MAX_HEADER_LIST_SIZE -> nextMaxHeaderListSize = value;
        default -> {
          // RFC 9113 §6.5.2: ignore unknown settings.
        }
      }
    }

    streamWindows.applyInitialWindowDelta(nextInitialWindowSize - initialWindowSize);
    headerTableSize = nextHeaderTableSize;
    pushEnabled = nextPushEnabled;
    maxConcurrentStreams = nextMaxConcurrentStreams;
    initialWindowSize = nextInitialWindowSize;
    maxFrameSize = nextMaxFrameSize;
    maxHeaderListSize = nextMaxHeaderListSize;
  }

  private static void validate(int id, long value) {
    switch (id) {
      case ENABLE_PUSH -> {
        if (value > 1) throw Http2Exception.PROTOCOL_ERROR;
      }
      case INITIAL_WINDOW_SIZE -> {
        if (value > Integer.MAX_VALUE) throw Http2Exception.FLOW_CONTROL_ERROR;
      }
      case MAX_FRAME_SIZE -> {
        if (value < 16_384 || value > 16_777_215) {
          throw Http2Exception.PROTOCOL_ERROR;
        }
      }
      default -> {
        // HEADER_TABLE_SIZE, MAX_CONCURRENT_STREAMS and MAX_HEADER_LIST_SIZE accept
        // every unsigned 32-bit value. Unknown identifiers are ignored by the RFC.
      }
    }
  }

  private static void checkRange(byte[] payload, int off, int len) {
    if (off < 0 || len < 0 || off > payload.length - len) {
      throw new IndexOutOfBoundsException("invalid SETTINGS payload range");
    }
  }

  private static int readUInt16(byte[] buf, int off) {
    return ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
  }

  private static long readUInt32(byte[] buf, int off) {
    return ((long) (buf[off] & 0xFF) << 24)
        | ((long) (buf[off + 1] & 0xFF) << 16)
        | ((long) (buf[off + 2] & 0xFF) << 8)
        | (buf[off + 3] & 0xFFL);
  }

  public int headerTableSize() {
    return headerTableSize;
  }

  public boolean pushEnabled() {
    return pushEnabled;
  }

  public long maxConcurrentStreams() {
    return maxConcurrentStreams;
  }

  public int initialWindowSize() {
    return initialWindowSize;
  }

  public int maxFrameSize() {
    return maxFrameSize;
  }

  public long maxHeaderListSize() {
    return maxHeaderListSize;
  }

  void reset() {
    headerTableSize = DEFAULT_HEADER_TABLE_SIZE;
    pushEnabled = true;
    maxConcurrentStreams = 0xFFFF_FFFFL;
    initialWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
    maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
    maxHeaderListSize = 0xFFFF_FFFFL;
  }
}
