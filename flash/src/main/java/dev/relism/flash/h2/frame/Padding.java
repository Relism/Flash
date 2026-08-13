package dev.relism.flash.h2.frame;

import dev.relism.flash.bytes.Pairs;
import dev.relism.flash.h2.Http2ErrorCode;
import dev.relism.flash.h2.Http2Exception;

/**
 * RFC 9113 §6.1 (DATA) / §6.2 (HEADERS) padding. When {@link FrameFlags#PADDED} is set, a
 * frame's payload is laid out as: 1 pad-length byte, then the actual data (or header-block
 * fragment), then that many padding bytes (RFC 9113 gives no meaning to the padding bytes
 * themselves — they exist only to obscure payload size from network observers).
 *
 * <p>Padding is <b>not optional to support</b>: any client may send it on DATA or HEADERS
 * regardless of whether the server ever sends padded frames itself.
 *
 * <h3>Flow control (forward note, not implemented here)</h3>
 * RFC 9113 §6.9.1: padding bytes count against the DATA flow-control window even though they
 * carry no data — the <em>whole</em> frame payload (pad-length byte + data + padding) is what a
 * future Phase 11 flow controller must subtract from the window, not just {@link
 * #dataLength(long)}. This class only locates the data range within the payload; it performs no
 * flow-control accounting itself.
 */
public final class Padding {
    private Padding() {}

    /**
     * Locates the actual data range within a payload that may or may not be padded. When
     * {@code padded} is {@code false}, returns the whole payload unchanged (zero-cost — no
     * padding byte to read, no arithmetic beyond the pack). When {@code true}, reads the
     * pad-length byte at {@code buf[payloadOffset]}, validates it, and returns the data range
     * that follows it.
     *
     * @return {@code Pairs.pack(dataOffset, dataLength)} — unpack with {@link Pairs#hi}/{@link Pairs#lo}
     * @throws Http2Exception ({@code PROTOCOL_ERROR}) if {@code padded} is set but
     *         {@code payloadLength == 0} (no room for the pad-length byte itself), or if the
     *         claimed pad length is greater than or equal to the whole payload length (RFC 9113
     *         §6.1: "If the length of the padding is the length of the frame payload or
     *         greater, the recipient MUST treat this as a connection error")
     */
    public static long unpad(byte[] buf, int payloadOffset, int payloadLength, boolean padded) {
        if (!padded) {
            return Pairs.pack(payloadOffset, payloadLength);
        }
        if (payloadLength == 0) {
            throw Http2Exception.of(Http2ErrorCode.PROTOCOL_ERROR,
                    "PADDED flag set but the frame has no payload for the pad-length byte");
        }
        int padLength = buf[payloadOffset] & 0xFF;
        if (padLength >= payloadLength) {
            throw Http2Exception.of(Http2ErrorCode.PROTOCOL_ERROR,
                    "pad length " + padLength + " >= frame payload length " + payloadLength);
        }
        int dataOffset = payloadOffset + 1;
        int dataLength = payloadLength - 1 - padLength;
        return Pairs.pack(dataOffset, dataLength);
    }

    /** Extracts the data offset from a value returned by {@link #unpad}. */
    public static int dataOffset(long unpadded) {
        return Pairs.hi(unpadded);
    }

    /** Extracts the data length from a value returned by {@link #unpad}. */
    public static int dataLength(long unpadded) {
        return Pairs.lo(unpadded);
    }
}
