package dev.relism.flash.bytes;

import dev.relism.fpr.core.ByteView;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * The single home for protocol-neutral byte scanning: single-byte search, the four-byte
 * {@code \r\n\r\n} header-terminator search (SWAR-accelerated), case-insensitive comparison,
 * comma-separated token-list scanning ({@code Connection: a, b, c}), RFC 9110 {@code tchar}
 * validation, and the case-insensitive header-name hash {@link dev.relism.flash.models.Http1HeaderMap}'s
 * index uses ({@code EX-09}).
 *
 * <p>Every method here is {@code static} and allocates nothing. Every SWAR method has a plain
 * scalar counterpart ({@code *Scalar}) that exists for two reasons: it is what the tests use as
 * the correctness oracle (property-tested against the SWAR version on randomized inputs — see
 * {@code ByteScanTest}/{@code ByteScanFuzzTest}), and it is the documented fallback if a future
 * measurement ever shows the SWAR path is not worth its complexity on some path (none has been
 * found not worth it so far — see {@code DECISIONS.md} for the one path that {@em was}
 * measured and kept, {@code EX-33}).
 *
 * <h3>The SWAR technique used throughout</h3>
 * Both {@link #indexOf} and {@link #indexOfCrLfCrLf} use the classic "does this word contain
 * byte {@code b}" bit trick (Bit Twiddling Hacks, "Determine if a word has a byte equal to n"):
 * XOR the 8-byte word against {@code b} broadcast into every lane (turning matching lanes to
 * {@code 0x00}), then test for any zero lane with
 * {@code (v - 0x0101010101010101L) & ~v & 0x8080808080808080L} — non-zero exactly when some lane
 * was {@code 0x00} before the subtraction, i.e. some original lane equalled {@code b}. This finds
 * *that a* matching lane exists in one word-sized read plus a handful of ALU ops, touching every
 * byte only once per 8-byte stride in the common (no-match-yet) case, instead of once per byte.
 *
 * <p>Reading the word uses {@link MethodHandles#byteArrayViewVarHandle} with
 * {@link ByteOrder#nativeOrder()} — deliberately native rather than a fixed order (contrast
 * {@code fpr-core}'s {@code ByteCompare}, which fixes {@code LITTLE_ENDIAN} because it compares
 * two independently-read words for bit-exact equality and so needs a byte order both reads
 * agree on; nothing here compares across two separately-decoded words, so the fastest order for
 * the host CPU is free to use). Byte-equality detection itself (finding that a matching lane
 * exists in the mask) does not depend on which order was used to assemble the word — XOR and the
 * haszero test are lane-wise operations, indifferent to how lanes map to memory offsets.
 * <b>Position extraction does depend on it</b>: converting "which bit of the 64-bit mask is set"
 * back into "which array index did that byte come from" requires knowing whether array byte 0
 * became the long's least-significant byte (little-endian) or most-significant byte
 * (big-endian) — {@link #laneIndexOf} branches on {@link #NATIVE_IS_LITTLE} once, at class-init
 * time, precisely to get this right on either host.
 */
public final class ByteScan {
    private ByteScan() {}

    private static final ByteOrder NATIVE_ORDER = ByteOrder.nativeOrder();
    private static final boolean NATIVE_IS_LITTLE = NATIVE_ORDER == ByteOrder.LITTLE_ENDIAN;
    private static final VarHandle LONG_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, NATIVE_ORDER);

    private static final long LANE_LSB = 0x0101010101010101L;
    private static final long LANE_MSB = 0x8080808080808080L;

    // ── tchar (RFC 9110 §5.6.2) ──────────────────────────────────────────────

    /**
     * RFC 9110 §5.6.2 {@code tchar} set, table-driven so validation is a single array read per
     * byte (R4/R5) rather than a chain of range comparisons. Indexed directly by byte value;
     * only the ASCII range a valid header-name character can ever occupy is populated.
     */
    private static final boolean[] TCHAR = new boolean[128];

    static {
        for (byte b : "!#$%&'*+-.^_`|~".getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
            TCHAR[b] = true;
        }
        for (char c = '0'; c <= '9'; c++) TCHAR[c] = true;
        for (char c = 'A'; c <= 'Z'; c++) TCHAR[c] = true;
        for (char c = 'a'; c <= 'z'; c++) TCHAR[c] = true;
    }

    /** Whether {@code b} is a valid RFC 9110 §5.6.2 {@code tchar} (a legal header-name byte). */
    public static boolean isTChar(byte b) {
        return b >= 0 && b < 128 && TCHAR[b];
    }

    // ── Single-byte search ───────────────────────────────────────────────────

    /**
     * Index of the first occurrence of {@code target} in {@code buf[from, to)}, or {@code -1}.
     * SWAR-accelerated: touches 8 bytes per word while no match has been found, falling back to
     * a byte-at-a-time tail once fewer than 8 bytes remain.
     */
    public static int indexOf(byte[] buf, int from, int to, byte target) {
        long broadcast = (target & 0xFFL) * LANE_LSB;
        int i = from;
        while (i + 8 <= to) {
            long word = (long) LONG_VIEW.get(buf, i);
            long masked = hasZeroLane(word ^ broadcast);
            if (masked != 0) {
                return i + laneIndexOf(masked);
            }
            i += 8;
        }
        for (; i < to; i++) {
            if (buf[i] == target) return i;
        }
        return -1;
    }

    /** Plain byte-at-a-time reference implementation of {@link #indexOf} — the test oracle. */
    static int indexOfScalar(byte[] buf, int from, int to, byte target) {
        for (int i = from; i < to; i++) {
            if (buf[i] == target) return i;
        }
        return -1;
    }

    // ── \r\n\r\n header terminator search ────────────────────────────────────

    private static final byte CR = '\r', LF = '\n';

    /**
     * Index of the first {@code "\r\n\r\n"} in {@code buf[from, to)}, or {@code -1}. SWAR
     * pre-filter (find a candidate {@code CR} byte 8 at a time) plus a cheap scalar 3-byte
     * verify at each candidate — see the class Javadoc for the technique and
     * {@code RequestParser}, {@code EX-33}, for why this replaced a fully byte-at-a-time scan.
     */
    public static int indexOfCrLfCrLf(byte[] buf, int from, int to) {
        int limit = to - 4; // last index at which a 4-byte match can start
        int i = from;
        while (i + 8 <= to) {
            long word = (long) LONG_VIEW.get(buf, i);
            long masked = hasZeroLane(word ^ CR_BROADCAST);
            if (masked == 0) {
                i += 8;
                continue;
            }
            int crPos = i + laneIndexOf(masked);
            if (crPos > limit) {
                // Nearest CR candidate in this word can't fit a full match before `to`; no CR
                // exists before it in [i, crPos) (laneIndexOf always finds the lowest-address
                // match first), so nothing in [i, crPos) can match either — the scalar tail
                // below, bounded by `limit`, correctly finds nothing without re-deriving that.
                break;
            }
            if (buf[crPos + 1] == LF && buf[crPos + 2] == CR && buf[crPos + 3] == LF) {
                return crPos;
            }
            i = crPos + 1;
        }
        for (; i <= limit; i++) {
            if (buf[i] == CR && buf[i + 1] == LF && buf[i + 2] == CR && buf[i + 3] == LF) {
                return i;
            }
        }
        return -1;
    }

    private static final long CR_BROADCAST = (CR & 0xFFL) * LANE_LSB;

    /** Plain byte-at-a-time reference implementation of {@link #indexOfCrLfCrLf} — the test oracle. */
    static int indexOfCrLfCrLfScalar(byte[] buf, int from, int to) {
        for (int i = from; i <= to - 4; i++) {
            if (buf[i] == CR && buf[i + 1] == LF && buf[i + 2] == CR && buf[i + 3] == LF) {
                return i;
            }
        }
        return -1;
    }

    /** "Determine if a word has a byte equal to n" (Bit Twiddling Hacks), applied to {@code xored}. */
    private static long hasZeroLane(long xored) {
        return (xored - LANE_LSB) & ~xored & LANE_MSB;
    }

    /** Converts a {@link #hasZeroLane} result into the array-index offset of its lowest matching lane. */
    private static int laneIndexOf(long masked) {
        return NATIVE_IS_LITTLE
                ? Long.numberOfTrailingZeros(masked) >>> 3
                : 7 - (Long.numberOfLeadingZeros(masked) >>> 3);
    }

    // ── Case-insensitive comparison ──────────────────────────────────────────

    private static byte foldAsciiUpper(byte b) {
        return (b >= 'A' && b <= 'Z') ? (byte) (b + 32) : b;
    }

    /** Case-insensitive (ASCII) equality of {@code buf[start, end)} against {@code target}. */
    public static boolean equalsIgnoreCaseAscii(byte[] buf, int start, int end, String target) {
        int len = end - start;
        if (len != target.length()) return false;
        for (int i = 0; i < len; i++) {
            if (foldAsciiUpper(buf[start + i]) != foldAsciiUpper((byte) target.charAt(i))) return false;
        }
        return true;
    }

    /** Case-insensitive (ASCII) equality of two byte-array ranges. */
    public static boolean equalsIgnoreCaseAscii(byte[] a, int aStart, int aLen, byte[] b, int bStart, int bLen) {
        if (aLen != bLen) return false;
        for (int i = 0; i < aLen; i++) {
            if (foldAsciiUpper(a[aStart + i]) != foldAsciiUpper(b[bStart + i])) return false;
        }
        return true;
    }

    /** Case-insensitive (ASCII) equality of {@code view[start, end)} against {@code target}. */
    public static boolean equalsIgnoreCase(ByteView view, int start, int end, String target) {
        int len = end - start;
        if (len != target.length()) return false;
        for (int i = 0; i < len; i++) {
            if (foldAsciiUpper(view.byteAt(start + i)) != foldAsciiUpper((byte) target.charAt(i))) return false;
        }
        return true;
    }

    // ── Comma-separated token lists (e.g. `Connection: keep-alive, Upgrade`) ────

    /**
     * Whether the comma-separated, OWS-tolerant token list {@code view} contains {@code token}
     * (case-insensitive). The shared scanner behind both {@code Http1KeepAlive.isKeepAlive} and
     * the {@code Connection: Upgrade} check ({@code EX-13}) — a single home so the two can never
     * drift apart the way a whole-value {@code equals} check once did.
     */
    public static boolean tokenListContains(ByteView view, String token) {
        int len = view.length(), i = 0;
        while (i < len) {
            while (i < len && view.byteAt(i) == ' ') i++;
            int start = i;
            while (i < len && view.byteAt(i) != ',') i++;
            if (tokenEqualsIgnoreCase(view, start, i, token)) return true;
            i++;
        }
        return false;
    }

    /** Case-insensitive compare of {@code view[start, end)}, trimming trailing spaces, against {@code token}. */
    public static boolean tokenEqualsIgnoreCase(ByteView view, int start, int end, String token) {
        int wlen = end - start;
        while (wlen > 0 && view.byteAt(start + wlen - 1) == ' ') wlen--;
        return equalsIgnoreCase(view, start, start + wlen, token);
    }

    // ── Header-name hash (EX-09) ─────────────────────────────────────────────

    /**
     * Case-insensitive (ASCII fold) 32-bit FNV-1a hash of {@code buf[start, start + len)}. Used
     * by {@link dev.relism.flash.models.Http1HeaderMap}'s per-request index to compare a cheap hash
     * before falling back to a full case-insensitive {@code memcmp}-equivalent
     * ({@link #equalsIgnoreCaseAscii}) — two header names that differ anywhere hash differently
     * with overwhelming probability, so the common "not the header I'm looking for" case resolves
     * in one hash compare instead of a byte-by-byte scan.
     */
    public static int hashNameIgnoreCaseAscii(byte[] buf, int start, int len) {
        int hash = 0x811C9DC5; // FNV-1a 32-bit offset basis
        for (int i = 0; i < len; i++) {
            hash ^= (foldAsciiUpper(buf[start + i]) & 0xFF);
            hash *= 0x01000193; // FNV-1a 32-bit prime
        }
        return hash;
    }

    /**
     * Same hash as {@link #hashNameIgnoreCaseAscii(byte[], int, int)}, computed directly from a
     * lookup-key {@code String} (e.g. {@code "Content-Type"}) instead of already-scanned bytes —
     * the two must agree bit-for-bit on equivalent ASCII content for
     * {@link dev.relism.flash.models.Http1HeaderMap}'s index (hash the request-declared bytes once at
     * {@code reset()}; hash the caller's lookup key once per {@code first()}/{@code all()} call;
     * compare the two cheap hashes before ever touching a full case-insensitive comparison).
     */
    public static int hashNameIgnoreCaseAscii(String name) {
        int hash = 0x811C9DC5;
        int len = name.length();
        for (int i = 0; i < len; i++) {
            hash ^= (foldAsciiUpper((byte) name.charAt(i)) & 0xFF);
            hash *= 0x01000193;
        }
        return hash;
    }

    // ── Decimal / hex parsing ────────────────────────────────────────────────

    /** Sentinel returned by {@link #parseDecimalStrict} on any malformed or out-of-range input. */
    public static final long PARSE_INVALID = -1L;

    /**
     * Strict, overflow-safe unsigned decimal parse of {@code buf[start, end)}: rejects an empty
     * range, any non-{@code '0'..'9'} byte, more than 19 digits, and arithmetic overflow past
     * {@link Long#MAX_VALUE}. Returns {@link #PARSE_INVALID} rather than throwing — the same
     * shape {@code RequestParser}'s own {@code Content-Length} parser already hand-rolls (kept
     * separate there since it also needs to throw a specific, differently-worded
     * {@code MalformedRequestException} per failure mode); this is the general-purpose version
     * for callers (HPACK integer decoding, frame-length fields) that just need a valid/invalid
     * signal.
     */
    public static long parseDecimalStrict(byte[] buf, int start, int end) {
        int len = end - start;
        if (len == 0 || len > 19) return PARSE_INVALID;
        long value = 0;
        for (int i = start; i < end; i++) {
            byte c = buf[i];
            if (c < '0' || c > '9') return PARSE_INVALID;
            int digit = c - '0';
            if (value > (Long.MAX_VALUE - digit) / 10) return PARSE_INVALID;
            value = value * 10 + digit;
        }
        return value;
    }

    /**
     * Parses up to {@code maxDigits} hex digits (ASCII, either case) from {@code buf[start, end)}
     * as an unsigned value. Returns {@link #PARSE_INVALID} if the range is empty, contains a
     * non-hex-digit byte, or would need more than {@code maxDigits} digits to represent (the
     * caller's bound against, e.g., a chunk-size line with an implausible number of digits).
     */
    public static long parseHexStrict(byte[] buf, int start, int end, int maxDigits) {
        int len = end - start;
        if (len == 0 || len > maxDigits) return PARSE_INVALID;
        long value = 0;
        for (int i = start; i < end; i++) {
            int digit = hexDigit(buf[i]);
            if (digit < 0) return PARSE_INVALID;
            value = (value << 4) | digit;
        }
        return value;
    }

    private static int hexDigit(byte b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        return -1;
    }
}
