package dev.relism.flash.http2.hpack;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.Http2Exception;

/**
 * RFC 7541 §5.2 / Appendix B: the fixed canonical Huffman code used to compress HPACK string
 * literals. {@link #CODES}/{@link #LENGTHS} are transcribed verbatim from Appendix B (each pair
 * cross-checked against the RFC's own "code as hex" / "code as bits" columns, which the RFC gives
 * redundantly for exactly this reason — a transcription error in either column disagrees with the
 * other). Every other structure in this class — the decode trie, the nibble-driven FSM, the
 * padding-validity table — is <b>built from that one 257-row table at class-init time</b>, not
 * hand-derived, so a mistake in this class's own logic (as opposed to the RFC table itself) shows
 * up as a decode/round-trip test failure rather than a silently wrong hand-written FSM.
 *
 * <h3>Decoding: a nibble-driven FSM</h3>
 *
 * {@link #decode} processes each input byte as two 4-bit nibbles (high nibble, then low), doing one
 * array lookup per nibble instead of one branch per bit. Each {@link #TRANSITIONS} entry packs: the
 * next trie state, whether a symbol was completed while consuming this nibble's 4 bits (at most one
 * — the shortest real code is 5 bits, longer than a nibble, so two symbols can never complete
 * within a single nibble transition, see {@link #buildTransitionTable} for the proof this relies
 * on), and that symbol's byte value if so. A "dead" transition (this nibble's bits cannot be a
 * prefix of any valid code, at this position) is a distinct packed flag the decode loop checks
 * first.
 *
 * <h3>Padding (RFC 7541 §5.2)</h3>
 *
 * A Huffman-coded string is padded to a byte boundary with the high-order bits of the EOS code (all
 * 1s), strictly fewer than 8 of them. Inserting the EOS code itself into the trie (as a real, if
 * never-emittable, leaf) means every prefix of the all-1s path already exists as a trie node from
 * ordinary trie construction — {@link #buildPaddingValidity} marks exactly those nodes (reachable
 * only via 1-bits from the root, depth 1..7) as valid end-of-input states. Anything else left over
 * when the input ends — an incomplete real code, or 8+ bits of trailing 1s — is {@code
 * COMPRESSION_ERROR}, and so is the EOS symbol appearing anywhere in the input (RFC 7541 §5.2: "a
 * Huffman-encoded string literal containing the EOS symbol MUST be treated as a decoding error").
 */
public final class Huffman {

  private Huffman() {}

  /**
   * Symbol id used internally for the EOS code (RFC 7541 Appendix B, row 256) — one past the last
   * real byte value; never a legal decode output.
   */
  private static final int EOS_SYMBOL = 256;

  // RFC 7541 Appendix B, verbatim: CODES[s]/LENGTHS[s] is symbol s's code (LSB-aligned, per the
  // RFC's own "code as hex" column) and its bit length, for s in [0, 255] plus EOS at s = 256.
  private static final int[] CODES = {
    0x1ff8, 0x7fffd8, 0xfffffe2, 0xfffffe3, 0xfffffe4, 0xfffffe5, 0xfffffe6, 0xfffffe7, 0xfffffe8,
        0xffffea,
    0x3ffffffc, 0xfffffe9, 0xfffffea, 0x3ffffffd, 0xfffffeb, 0xfffffec, 0xfffffed, 0xfffffee,
        0xfffffef, 0xffffff0,
    0xffffff1, 0xffffff2, 0x3ffffffe, 0xffffff3, 0xffffff4, 0xffffff5, 0xffffff6, 0xffffff7,
        0xffffff8, 0xffffff9,
    0xffffffa, 0xffffffb, 0x14, 0x3f8, 0x3f9, 0xffa, 0x1ff9, 0x15, 0xf8, 0x7fa,
    0x3fa, 0x3fb, 0xf9, 0x7fb, 0xfa, 0x16, 0x17, 0x18, 0x0, 0x1,
    0x2, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x5c, 0xfb,
    0x7ffc, 0x20, 0xffb, 0x3fc, 0x1ffa, 0x21, 0x5d, 0x5e, 0x5f, 0x60,
    0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a,
    0x6b, 0x6c, 0x6d, 0x6e, 0x6f, 0x70, 0x71, 0x72, 0xfc, 0x73,
    0xfd, 0x1ffb, 0x7fff0, 0x1ffc, 0x3ffc, 0x22, 0x7ffd, 0x3, 0x23, 0x4,
    0x24, 0x5, 0x25, 0x26, 0x27, 0x6, 0x74, 0x75, 0x28, 0x29,
    0x2a, 0x7, 0x2b, 0x76, 0x2c, 0x8, 0x9, 0x2d, 0x77, 0x78,
    0x79, 0x7a, 0x7b, 0x7ffe, 0x7fc, 0x3ffd, 0x1ffd, 0xffffffc, 0xfffe6, 0x3fffd2,
    0xfffe7, 0xfffe8, 0x3fffd3, 0x3fffd4, 0x3fffd5, 0x7fffd9, 0x3fffd6, 0x7fffda, 0x7fffdb,
        0x7fffdc,
    0x7fffdd, 0x7fffde, 0xffffeb, 0x7fffdf, 0xffffec, 0xffffed, 0x3fffd7, 0x7fffe0, 0xffffee,
        0x7fffe1,
    0x7fffe2, 0x7fffe3, 0x7fffe4, 0x1fffdc, 0x3fffd8, 0x7fffe5, 0x3fffd9, 0x7fffe6, 0x7fffe7,
        0xffffef,
    0x3fffda, 0x1fffdd, 0xfffe9, 0x3fffdb, 0x3fffdc, 0x7fffe8, 0x7fffe9, 0x1fffde, 0x7fffea,
        0x3fffdd,
    0x3fffde, 0xfffff0, 0x1fffdf, 0x3fffdf, 0x7fffeb, 0x7fffec, 0x1fffe0, 0x1fffe1, 0x3fffe0,
        0x1fffe2,
    0x7fffed, 0x3fffe1, 0x7fffee, 0x7fffef, 0xfffea, 0x3fffe2, 0x3fffe3, 0x3fffe4, 0x7ffff0,
        0x3fffe5,
    0x3fffe6, 0x7ffff1, 0x3ffffe0, 0x3ffffe1, 0xfffeb, 0x7fff1, 0x3fffe7, 0x7ffff2, 0x3fffe8,
        0x1ffffec,
    0x3ffffe2, 0x3ffffe3, 0x3ffffe4, 0x7ffffde, 0x7ffffdf, 0x3ffffe5, 0xfffff1, 0x1ffffed, 0x7fff2,
        0x1fffe3,
    0x3ffffe6, 0x7ffffe0, 0x7ffffe1, 0x3ffffe7, 0x7ffffe2, 0xfffff2, 0x1fffe4, 0x1fffe5, 0x3ffffe8,
        0x3ffffe9,
    0xffffffd, 0x7ffffe3, 0x7ffffe4, 0x7ffffe5, 0xfffec, 0xfffff3, 0xfffed, 0x1fffe6, 0x3fffe9,
        0x1fffe7,
    0x1fffe8, 0x7ffff3, 0x3fffea, 0x3fffeb, 0x1ffffee, 0x1ffffef, 0xfffff4, 0xfffff5, 0x3ffffea,
        0x7ffff4,
    0x3ffffeb, 0x7ffffe6, 0x3ffffec, 0x3ffffed, 0x7ffffe7, 0x7ffffe8, 0x7ffffe9, 0x7ffffea,
        0x7ffffeb, 0xffffffe,
    0x7ffffec, 0x7ffffed, 0x7ffffee, 0x7ffffef, 0x7fffff0, 0x3ffffee, 0x3fffffff,
  };

  private static final int[] LENGTHS = {
    13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28, 28, 28, 28, 28,
    28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28, 6, 10, 10, 12, 13, 6, 8, 11,
    10, 10, 8, 11, 8, 6, 6, 6, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 7, 8,
    15, 6, 12, 10, 13, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 7, 7, 7, 7, 7, 7, 8, 7, 8, 13, 19, 13, 14, 6, 15, 5, 6, 5,
    6, 5, 6, 6, 6, 5, 7, 7, 6, 6, 6, 5, 6, 7, 6, 5, 5, 6, 7, 7,
    7, 7, 7, 15, 11, 14, 13, 28, 20, 22, 20, 20, 22, 22, 22, 23, 22, 23, 23, 23,
    23, 23, 24, 23, 24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23, 24,
    22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23, 21, 21, 22, 21,
    23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23, 26, 26, 20, 19, 22, 23, 22, 25,
    26, 26, 26, 27, 27, 26, 24, 25, 19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26,
    28, 27, 27, 27, 20, 24, 20, 21, 22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23,
    26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27, 27, 27, 27, 26, 30,
  };

  // ── Trie, built once from CODES/LENGTHS ─────────────────────────────────────

  private static final int ROOT = 0;
  private static final int NO_CHILD = -1;
  private static final int NO_SYMBOL = -1;

  private static final int[] child0;
  private static final int[] child1;
  private static final int[] symbolAt;
  private static final int[] depthOf;
  private static final boolean[] onesSpine;
  private static final int nodeCount;

  // ── Nibble-driven FSM, built from the trie above ────────────────────────────

  private static final int STATE_BITS = 16;
  private static final int STATE_MASK = (1 << STATE_BITS) - 1;
  private static final int FLAG_SYMBOL = 1 << STATE_BITS;
  private static final int FLAG_INVALID = 1 << (STATE_BITS + 1);
  private static final int SYMBOL_SHIFT = 24;

  private static final int[] TRANSITIONS;
  private static final boolean[] validEndState;

  static {
    // Build the trie: one node per distinct bit-prefix any of the 257 codes passes through.
    // Sized generously (sum of code lengths bounds the true worst case; the actual codes
    // share far more prefix structure than that bound suggests).
    int capacity = 4096;
    int[] c0 = new int[capacity];
    int[] c1 = new int[capacity];
    int[] sym = new int[capacity];
    int[] depth = new int[capacity];
    boolean[] spine = new boolean[capacity];
    java.util.Arrays.fill(c0, NO_CHILD);
    java.util.Arrays.fill(c1, NO_CHILD);
    java.util.Arrays.fill(sym, NO_SYMBOL);
    spine[ROOT] = true;
    int[] count = {1}; // node 0 = root, already allocated

    for (int s = 0; s <= EOS_SYMBOL; s++) {
      insert(c0, c1, sym, depth, spine, count, CODES[s], LENGTHS[s], s);
    }

    nodeCount = count[0];
    child0 = java.util.Arrays.copyOf(c0, nodeCount);
    child1 = java.util.Arrays.copyOf(c1, nodeCount);
    symbolAt = java.util.Arrays.copyOf(sym, nodeCount);
    depthOf = java.util.Arrays.copyOf(depth, nodeCount);
    onesSpine = java.util.Arrays.copyOf(spine, nodeCount);

    if (nodeCount > (1 << STATE_BITS)) {
      // Defensive: would only trip if a future edit changed the table shape drastically.
      throw new ExceptionInInitializerError(
          "Huffman trie grew to " + nodeCount + " nodes, exceeding STATE_BITS budget");
    }

    TRANSITIONS = buildTransitionTable();
    validEndState = buildPaddingValidity();
  }

  private static void insert(
      int[] c0,
      int[] c1,
      int[] sym,
      int[] depth,
      boolean[] spine,
      int[] count,
      int code,
      int length,
      int symbolValue) {
    int node = ROOT;
    for (int i = length - 1; i >= 0; i--) {
      int bit = (code >>> i) & 1;
      int[] children = bit == 0 ? c0 : c1;
      int next = children[node];
      if (next == NO_CHILD) {
        next = count[0]++;
        depth[next] = depth[node] + 1;
        spine[next] = spine[node] && bit == 1;
        children[node] = next;
      }
      node = next;
    }
    sym[node] = symbolValue;
  }

  /**
   * Builds the {@code state * 16 + nibble} transition table. For each state and each possible 4-bit
   * nibble value, walks up to 4 trie edges from that state, MSB-first within the nibble.
   *
   * <p><b>Why at most one symbol per nibble</b>: the shortest real code in {@link #LENGTHS} is 5
   * bits (verified by {@code HuffmanTest.everyRealCodeIsAtLeastFiveBitsLong} — the invariant this
   * method's design depends on). Completing a symbol mid-nibble consumes at least 1 of the nibble's
   * 4 bits; whatever remains (at most 3) is too short to complete a second code from a fresh root.
   * So this method never needs to track more than one emission per entry.
   *
   * <p>A missing child edge (a bit sequence that is not a prefix of any of the 257 codes) is always
   * {@code FLAG_INVALID}, unconditionally — including at what turns out to be the last nibble of
   * the input. This is intentional, not an approximation: valid padding never causes a missing-edge
   * walk to begin with (see {@link #buildPaddingValidity}) — it only ever causes the input to run
   * out while sitting at a legitimate partial state, which this method's caller ({@link #decode})
   * checks separately once the whole input has been consumed.
   */
  private static int[] buildTransitionTable() {
    int[] table = new int[nodeCount * 16];
    for (int state = 0; state < nodeCount; state++) {
      for (int nibble = 0; nibble < 16; nibble++) {
        table[state * 16 + nibble] = simulateNibble(state, nibble);
      }
    }
    return table;
  }

  private static int simulateNibble(int startState, int nibble) {
    int state = startState;
    boolean emitted = false;
    int emittedSymbol = -1;
    for (int bitIndex = 3; bitIndex >= 0; bitIndex--) {
      int bit = (nibble >>> bitIndex) & 1;
      int next = bit == 0 ? child0[state] : child1[state];
      if (next == NO_CHILD) {
        return FLAG_INVALID;
      }
      state = next;
      if (symbolAt[state] != NO_SYMBOL) {
        if (symbolAt[state] == EOS_SYMBOL) {
          return FLAG_INVALID; // RFC 7541 5.2: EOS in the input is always an error
        }
        emitted = true;
        emittedSymbol = symbolAt[state];
        state = ROOT; // remaining bits of this nibble (if any) start a fresh code
      }
    }
    int packed = state;
    if (emitted) {
      packed |= FLAG_SYMBOL | (emittedSymbol << SYMBOL_SHIFT);
    }
    return packed;
  }

  /**
   * {@code validEndState[s]}: {@code true} if the decoder may legally have consumed all input while
   * sitting at trie state {@code s} — root (nothing pending) or 1..7 bits into the all-1s
   * (EOS-prefix) spine.
   */
  private static boolean[] buildPaddingValidity() {
    boolean[] valid = new boolean[nodeCount];
    valid[ROOT] = true;
    for (int s = 1; s < nodeCount; s++) {
      valid[s] = onesSpine[s] && depthOf[s] <= 7;
    }
    return valid;
  }

  // ── Public API ───────────────────────────────────────────────────────────

  /**
   * Decodes the Huffman-coded string {@code src[srcOff, srcOff + srcLen)} into {@code dst[dstOff,
   * dstLimit)}, returning the number of bytes written. The output bound is enforced <em>as bytes
   * are produced</em>, not after accumulating into an unbounded buffer — callers pass a {@code
   * dst}/{@code dstLimit} sized to their own maximum (typically {@code
   * Http2Limits.MAX_HPACK_STRING_LENGTH}), and a string that would decode past it is rejected
   * mid-decode.
   *
   * @throws Http2Exception {@code COMPRESSION_ERROR} — on any bit sequence that is not a prefix of
   *     a real code, on the EOS symbol appearing in the input, on invalid trailing padding (not
   *     all-1s, or 8+ bits), or on exceeding {@code dstLimit}
   */
  public static int decode(
      byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff, int dstLimit) {
    int state = ROOT;
    int dstPos = dstOff;
    int end = srcOff + srcLen;
    for (int i = srcOff; i < end; i++) {
      int b = src[i] & 0xFF;

      int t = TRANSITIONS[state * 16 + (b >>> 4)];
      if ((t & FLAG_INVALID) != 0) throw Http2Exception.COMPRESSION_ERROR;
      if ((t & FLAG_SYMBOL) != 0) {
        if (dstPos >= dstLimit) throw Http2Exception.COMPRESSION_ERROR;
        dst[dstPos++] = (byte) (t >>> SYMBOL_SHIFT);
      }
      state = t & STATE_MASK;

      t = TRANSITIONS[state * 16 + (b & 0xF)];
      if ((t & FLAG_INVALID) != 0) throw Http2Exception.COMPRESSION_ERROR;
      if ((t & FLAG_SYMBOL) != 0) {
        if (dstPos >= dstLimit) throw Http2Exception.COMPRESSION_ERROR;
        dst[dstPos++] = (byte) (t >>> SYMBOL_SHIFT);
      }
      state = t & STATE_MASK;
    }
    if (!validEndState[state]) throw Http2Exception.COMPRESSION_ERROR;
    return dstPos - dstOff;
  }

  /**
   * Huffman-encodes {@code src[off, off + len)}, writing directly into {@code out}. Pads the final
   * byte with the high-order bits of the EOS code (all 1s), per RFC 7541 §5.2. Built now ({@code
   * EX} task 3) for use by the HPACK encoder.
   */
  public static void encode(ByteWriter out, byte[] src, int off, int len) {
    long accumulator = 0;
    int bitCount = 0;
    int end = off + len;
    for (int i = off; i < end; i++) {
      int v = src[i] & 0xFF;
      int codeLen = LENGTHS[v];
      accumulator = (accumulator << codeLen) | (CODES[v] & ((1L << codeLen) - 1));
      bitCount += codeLen;
      while (bitCount >= 8) {
        bitCount -= 8;
        out.writeByte((byte) (accumulator >>> bitCount));
      }
    }
    if (bitCount > 0) {
      int padBits = 8 - bitCount;
      long lastByte = ((accumulator << padBits) | ((1L << padBits) - 1)) & 0xFF;
      out.writeByte((byte) lastByte);
    }
  }

  /**
   * The number of bytes {@link #encode} would produce for {@code src[off, off + len)} — the ceiling
   * of the total bit length over 8.
   */
  public static int encodedLength(byte[] src, int off, int len) {
    long bits = 0;
    int end = off + len;
    for (int i = off; i < end; i++) {
      bits += LENGTHS[src[i] & 0xFF];
    }
    return (int) ((bits + 7) / 8);
  }
}
