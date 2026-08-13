package dev.relism.flash.http;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * {@code EX-16}: RFC 9110 §6.6.1 — an origin server with a clock SHOULD send {@code Date}.
 * Flash never emitted it. Rather than formatting a timestamp on every response, a single
 * daemon thread refreshes a pre-encoded {@code "Date: ...\r\n"} field line once per second into
 * a {@code volatile byte[]}; {@code Http1ResponseWriter} writes it with one
 * {@code OutputStream#write(byte[])} — the cost per response is one volatile read and one
 * write, never a format call (R4).
 *
 * <p>The parallel HPACK-encoded rendering for HTTP/2 responses is added in Phase 9.
 */
public final class DateHeader {

    private DateHeader() {
    }

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private static volatile byte[] current = encode();

    static {
        Thread refresher = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                current = encode();
            }
        }, "flash-date-header");
        refresher.setDaemon(true);
        refresher.start();
    }

    private static byte[] encode() {
        String line = "Date: " + FORMATTER.format(ZonedDateTime.now(ZoneOffset.UTC)) + "\r\n";
        return line.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * The current pre-encoded {@code "Date: ...\r\n"} field line, accurate to within one
     * second. Never allocates — the same array is returned until the next refresh.
     */
    public static byte[] bytes() {
        return current;
    }
}
