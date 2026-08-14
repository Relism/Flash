package dev.relism.flash.http2.frame;

import dev.relism.flash.http2.Http2ErrorCode;
import dev.relism.flash.http2.Http2Exception;
import dev.relism.flash.http2.Http2Limits;

/**
 * Table-driven RFC 9113 per-frame-type validation: length bounds, the stream-id
 * required/forbidden/either rule, and the two special-cased structural rules ({@code SETTINGS}'
 * multiple-of-6 length, {@code PUSH_PROMISE} always rejected from a client) that do not fit a
 * class is the code that reads it.
 *
 * <p><b>The error code is not uniform</b> — read the RFC per violation, not just per type. A
 * {@code SETTINGS} frame with a bad length is {@code FRAME_SIZE_ERROR}; the same frame with a
 * non-zero stream id is {@code PROTOCOL_ERROR}. This class throws the specific code each
 * violation's own RFC citation requires, not a single blanket code per type.
 */
public final class FrameValidator {
    private FrameValidator() {}

    /**
     * Validates {@code header} against RFC 9113's rules for its type.
     *
     * @param insideHeaderBlock whether this frame arrived between a HEADERS/PUSH_PROMISE frame
     *                          lacking {@code END_HEADERS} and its terminating CONTINUATION —
     *                          changes the handling of an unrecognised type (§6.10: a
     *                          {@code PROTOCOL_ERROR}, not the usual silent ignore, since an
     *                          in-progress header block cannot tolerate an interloper frame of
     *                          any kind without desynchronizing HPACK's stateful decode)
     * @throws Http2Exception on any RFC violation, with the specific error code the violated
     *         rule mandates
     */
    public static void validate(FrameHeader header, boolean insideHeaderBlock) {
        FrameType type = header.type();

        if (type == null) {
            // RFC 9113 §4.1: unknown frame types MUST be ignored — except inside an in-progress
            // header block (§6.10), where anything other than CONTINUATION desynchronizes HPACK.
            if (insideHeaderBlock) {
                throw Http2Exception.of(Http2ErrorCode.PROTOCOL_ERROR,
                        "unrecognised frame type " + header.typeCode() + " received inside a header block");
            }
            return;
        }

        int length = header.length();

        // RFC 9113 §6.5: a SETTINGS frame's length MUST be a multiple of 6 (each entry is a
        // 2-byte identifier + 4-byte value). Checked before the generic bounds below, since the
        // generic table only expresses a min/max range, not a modulus.
        if (type == FrameType.SETTINGS && length % 6 != 0) {
            throw Http2Exception.FRAME_SIZE_ERROR;
        }

        if (length < type.minLength() || length > type.maxLength()) {
            throw Http2Exception.FRAME_SIZE_ERROR;
        }

        // Redundant with Http2FrameReader's own pre-allocation check for frames it read itself,
        // but this method must also be correct for a FrameHeader built any other way (tests,
        // and — in later phases — frames reassembled from multiple reads), so the bound is
        // re-asserted here rather than trusted from the caller.
        if (length > Http2Limits.MAX_FRAME_SIZE_LOCAL) {
            throw Http2Exception.FRAME_SIZE_ERROR;
        }

        int streamId = header.streamId();
        switch (type.streamIdRule()) {
            case REQUIRED -> {
                if (streamId == 0) {
                    throw Http2Exception.of(Http2ErrorCode.PROTOCOL_ERROR, type + " requires a non-zero stream id");
                }
            }
            case FORBIDDEN -> {
                if (streamId != 0) {
                    throw Http2Exception.of(Http2ErrorCode.PROTOCOL_ERROR, type + " must have stream id 0, got " + streamId);
                }
            }
            case EITHER -> { /* WINDOW_UPDATE: 0 (connection window) or non-zero (stream window) both valid */ }
        }

        // (Flash advertises SETTINGS_ENABLE_PUSH=0 and never sends one); receiving one at all
        // means the peer believes it is talking to a client, which is always a protocol error.
        if (type == FrameType.PUSH_PROMISE) {
            throw Http2Exception.of(Http2ErrorCode.PROTOCOL_ERROR, "PUSH_PROMISE received from a client");
        }
    }
}
