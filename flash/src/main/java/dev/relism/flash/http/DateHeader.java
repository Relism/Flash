package dev.relism.flash.http;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.hpack.HpackEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

/**
 * A daemon refreshes both protocol renderings once per second. Response writers only perform one
 * volatile read and copy already-encoded bytes into their output buffer.
 */
public final class DateHeader {

  private DateHeader() {}

  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
          .withZone(ZoneOffset.UTC);

  private record Snapshot(byte[] http1, byte[] hpack) {}

  private static volatile Snapshot current = encode();

  static {
    Thread refresher =
        new Thread(
            () -> {
              while (true) {
                try {
                  Thread.sleep(1000);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                current = encode();
              }
            },
            "flash-date-header");
    refresher.setDaemon(true);
    refresher.start();
  }

  private static Snapshot encode() {
    byte[] value = format(ZonedDateTime.now(ZoneOffset.UTC)).getBytes(StandardCharsets.US_ASCII);
    byte[] prefix = "Date: ".getBytes(StandardCharsets.US_ASCII);
    byte[] http1 = new byte[prefix.length + value.length + 2];
    System.arraycopy(prefix, 0, http1, 0, prefix.length);
    System.arraycopy(value, 0, http1, prefix.length, value.length);
    http1[http1.length - 2] = '\r';
    http1[http1.length - 1] = '\n';

    ByteWriter encoded = new ByteWriter(32);
    HpackEncoder.writeLiteralWithNameIndex(encoded, 33, value, true);
    return new Snapshot(http1, Arrays.copyOf(encoded.array(), encoded.length()));
  }

  static String format(ZonedDateTime time) {
    return FORMATTER.format(time);
  }

  /**
   * The current pre-encoded {@code "Date: ...\r\n"} field line, accurate to within one second.
   * Never allocates — the same array is returned until the next refresh.
   */
  public static byte[] bytes() {
    return current.http1;
  }

  /** Current precompiled HPACK {@code date} field. */
  public static byte[] hpackBytes() {
    return current.hpack;
  }
}
