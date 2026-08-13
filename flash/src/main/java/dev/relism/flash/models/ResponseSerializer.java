package dev.relism.flash.models;

import java.nio.charset.StandardCharsets;

/**
 * The protocol-neutral enumeration of a {@link Response}'s header fields — one source of truth
 * consumed by every protocol's own writer, so {@code Content-Type}/custom-header logic is never
 * duplicated (and cannot drift) between {@code Http1ResponseWriter} and a future h2 encoder
 * (Phase 9). {@code Http1ResponseWriter} renders each field as {@code "Name: Value\r\n"}; the h2
 * encoder will render the same fields via HPACK.
 *
 * <h3>Scope: response-object fields only, not connection framing</h3>
 * Deliberately does <b>not</b> enumerate {@code Content-Length}, {@code Connection}, or
 * {@code Date} — those are connection/transport framing decisions (body length, keep-alive
 * negotiation, wall-clock time), not properties of the {@code Response} object itself, and HTTP/2
 * has no equivalent of {@code Connection} at all (RFC 9113 §8.2.2 forbids connection-specific
 * fields in h2). Each protocol's own writer computes and emits those itself, exactly as
 * {@code Http1ResponseWriter} already did before this class existed.
 *
 * <h3>Scope: excludes {@link Response#header(byte[])}'s legacy entries</h3>
 * A header added via the raw, fully-pre-rendered {@code header(byte[])} overload has no
 * recoverable (name, value) structure — see that method's own Javadoc — so it cannot appear in
 * this enumeration. {@code Http1ResponseWriter} still renders it (via {@link
 * Response#writeHeaders}, which handles both structured and raw entries, in the original call
 * order); a future h2 writer will not be able to.
 */
public final class ResponseSerializer {
    private ResponseSerializer() {}

    /** One rendered header field: a byte range for the name, and a byte range for the value — both slices of caller-owned arrays, never copied. */
    @FunctionalInterface
    public interface FieldConsumer {
        void accept(byte[] nameBuf, int nameOff, int nameLen, byte[] valueBuf, int valueOff, int valueLen);
    }

    private static final byte[] CONTENT_TYPE_NAME = "Content-Type".getBytes(StandardCharsets.US_ASCII);

    /**
     * Enumerates {@code response}'s fields in a fixed, deterministic order: {@code Content-Type}
     * first (if set to a non-empty value — {@code EX-15}: {@code ContentType.NONE} emits
     * nothing, never an empty-valued header line), then every {@code header(String,String)}/
     * {@code header(PreEncodedHeader)}-added field in call order. Zero allocation: every byte
     * range handed to {@code consumer} is a slice of {@code response}'s own already-allocated
     * buffers.
     */
    public static void forEachField(Response response, FieldConsumer consumer) {
        byte[] ct = response.getContentType();
        if (ct != null && ct.length > 0) {
            consumer.accept(CONTENT_TYPE_NAME, 0, CONTENT_TYPE_NAME.length, ct, 0, ct.length);
        }
        response.forEachStructuredField(consumer);
    }
}
