package dev.relism.flash.http2.hpack;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.bytes.Pairs;
import dev.relism.flash.http2.Http2Exception;

/**
 * RFC 7541 §5.1 prefix-coded integer encode/decode. An {@code N}-bit prefix holds values {@code
 * 0..2^N-2} directly; the sentinel {@code 2^N-1} means "the real value is at least this large, keep
 * reading continuation octets" — each contributes 7 bits, low-to-high, with the high bit as a
 * continue flag.
 *
 * <h3>Overflow safety ({@code HPACK bomb})</h3>
 *
 * RFC 7541 places no upper bound on the number of continuation octets — a hostile peer can encode
 * an arbitrarily large integer (conceptually up to 2^64 and beyond) in a handful of bytes. {@link
 * #decode} rejects any integer needing more than {@link #MAX_CONTINUATION_OCTETS} continuation
 * octets, and independently rejects one that would exceed {@link Integer#MAX_VALUE} even within
 * that octet budget — belt-and-suspenders, since with the octet cap in place the second check is
 * not expected to ever fire on a real input. Both throw the preallocated {@link
 * Http2Exception#COMPRESSION_ERROR} singleton — zero allocation on this hot rejection path (see
 * that exception's own Javadoc for why reusing a singleton here is safe).
 */
public final class HpackIntegers {

  private HpackIntegers() {}

  /**
   * Maximum number of continuation octets {@link #decode} accepts. 4 octets contribute {@code 4 * 7
   * = 28} bits beyond the prefix — comfortably enough for any legitimate HPACK integer (table
   * indices, string lengths, table size updates are all far smaller in practice) while keeping a
   * hostile peer's worst case bounded to a handful of wasted bytes per rejected block, not an
   * unbounded read loop.
   */
  private static final int MAX_CONTINUATION_OCTETS = 4;

  /**
   * Decodes a prefix-coded integer starting at {@code buf[pos]}, where the low {@code prefixBits}
   * bits of {@code buf[pos]} carry the prefix (any higher bits — e.g. a representation's leading
   * flag bits — are the caller's concern and are masked off here). {@code limit} is the exclusive
   * end of the region this integer may read from (typically the end of the current HPACK block) —
   * reading past it means a truncated/malformed encoding, not "need more input", since by the time
   * this runs the whole block is already contiguous in memory (RFC 9113 §6.10: CONTINUATION frames
   * are never interleaved with other frames).
   *
   * @return {@link Pairs#pack}({@code value}, {@code newPos}) — the decoded value in the high 32
   *     bits, the position just past the last consumed byte in the low 32 bits
   * @throws Http2Exception {@code COMPRESSION_ERROR} on truncation, on exceeding {@link
   *     #MAX_CONTINUATION_OCTETS}, or on a value that would exceed {@link Integer#MAX_VALUE}
   */
  public static long decode(byte[] buf, int pos, int limit, int prefixBits) {
    if (pos >= limit) throw Http2Exception.COMPRESSION_ERROR;
    int prefixMask = (1 << prefixBits) - 1;
    int first = buf[pos] & 0xFF;
    int value = first & prefixMask;
    int p = pos + 1;
    if (value < prefixMask) {
      return Pairs.pack(value, p);
    }

    long accumulated = prefixMask;
    int shift = 0;
    int continuationOctets = 0;
    while (true) {
      if (p >= limit) throw Http2Exception.COMPRESSION_ERROR;
      if (++continuationOctets > MAX_CONTINUATION_OCTETS) throw Http2Exception.COMPRESSION_ERROR;
      int b = buf[p++] & 0xFF;
      accumulated += (long) (b & 0x7F) << shift;
      if (accumulated > Integer.MAX_VALUE) throw Http2Exception.COMPRESSION_ERROR;
      if ((b & 0x80) == 0) break;
      shift += 7;
    }
    return Pairs.pack((int) accumulated, p);
  }

  /**
   * Encodes {@code value} as a prefix-coded integer into {@code prefixByteFlags | encoded value},
   * writing into {@code out}. {@code prefixByteFlags} carries whatever high bits the representation
   * needs (e.g. {@code 0x80} for an Indexed Header Field) already shifted into position — this
   * method only ever sets the low {@code prefixBits} bits of the first byte.
   */
  public static void encode(ByteWriter out, int prefixByteFlags, int prefixBits, int value) {
    int prefixMask = (1 << prefixBits) - 1;
    if (value < prefixMask) {
      out.writeByte((byte) (prefixByteFlags | value));
      return;
    }
    out.writeByte((byte) (prefixByteFlags | prefixMask));
    int remaining = value - prefixMask;
    while (remaining >= 0x80) {
      out.writeByte((byte) ((remaining & 0x7F) | 0x80));
      remaining >>>= 7;
    }
    out.writeByte((byte) remaining);
  }
}
