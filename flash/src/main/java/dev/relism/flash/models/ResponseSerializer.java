package dev.relism.flash.models;

import java.nio.charset.StandardCharsets;

/**
 * The protocol-neutral enumeration of a {@link Response}'s header fields — one source of truth
 * consumed by every protocol's writer, so field selection cannot drift between HTTP/1 and HTTP/2.
 *
 * <h3>Scope: response-object fields only, not connection framing</h3>
 *
 * Deliberately does <b>not</b> enumerate {@code Content-Length}, {@code Connection}, or {@code
 * Date} — those are connection/transport framing decisions (body length, keep-alive negotiation,
 * wall-clock time), not properties of the {@code Response} object itself, and HTTP/2 has no
 * equivalent of {@code Connection} at all (RFC 9113 §8.2.2 forbids connection-specific fields in
 * h2). Each protocol's own writer computes and emits those itself, exactly as {@code
 * Http1ResponseWriter} already did before this class existed.
 *
 * <h3>Scope: excludes {@link Response#header(byte[])}'s legacy entries</h3>
 *
 * A header added via the raw, fully-pre-rendered {@code header(byte[])} overload has no recoverable
 * (name, value) structure — see that method's own Javadoc — so it cannot appear in this
 * enumeration. HTTP/1 still renders it via {@link Response#writeHeaders}; HTTP/2 cannot recover its
 * field structure and ignores it.
 */
public final class ResponseSerializer {
  private ResponseSerializer() {}

  /**
   * One rendered header field: a byte range for the name, and a byte range for the value — both
   * slices of caller-owned arrays, never copied.
   */
  @FunctionalInterface
  public interface FieldConsumer {
    void accept(
        byte[] nameBuf, int nameOff, int nameLen, byte[] valueBuf, int valueOff, int valueLen);
  }

  private static final byte[] CONTENT_TYPE_NAME =
      "Content-Type".getBytes(StandardCharsets.US_ASCII);

  /**
   * Enumerates {@code response}'s fields in a fixed order: non-empty {@code Content-Type}, then
   * structured custom fields in call order. Every range is a slice of existing response storage.
   */
  public static void forEachField(Response response, FieldConsumer consumer) {
    byte[] ct = response.getContentType();
    if (ct != null && ct.length > 0) {
      consumer.accept(CONTENT_TYPE_NAME, 0, CONTENT_TYPE_NAME.length, ct, 0, ct.length);
    }
    forEachCustomField(response, consumer);
  }

  /** Enumerates structured custom fields only, excluding {@code content-type}. */
  public static void forEachCustomField(Response response, FieldConsumer consumer) {
    response.forEachStructuredField(consumer);
  }

  /** Enumerates response trailers in declaration order. */
  public static void forEachTrailerField(Response response, FieldConsumer consumer) {
    response.forEachTrailerField(consumer);
  }
}
