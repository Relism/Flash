package dev.relism.flash.bytes;

import dev.relism.fpr.core.ByteView;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ByteScanTest {

    private static final class ArrayView implements ByteView {
        final byte[] buf;
        ArrayView(String s) { this.buf = s.getBytes(StandardCharsets.US_ASCII); }
        @Override public int length() { return buf.length; }
        @Override public byte byteAt(int i) { return buf[i]; }
    }

    // ── isTChar ──────────────────────────────────────────────────────────────

    @Test
    void isTChar_acceptsRfc9110TCharSet() {
        for (char c = '0'; c <= '9'; c++) assertTrue(ByteScan.isTChar((byte) c));
        for (char c = 'A'; c <= 'Z'; c++) assertTrue(ByteScan.isTChar((byte) c));
        for (char c = 'a'; c <= 'z'; c++) assertTrue(ByteScan.isTChar((byte) c));
        for (byte b : "!#$%&'*+-.^_`|~".getBytes(StandardCharsets.US_ASCII)) assertTrue(ByteScan.isTChar(b));
    }

    @Test
    void isTChar_rejectsDelimitersAndControlAndHighBytes() {
        for (byte b : " \t\":;,()<>[]{}=?@\\/".getBytes(StandardCharsets.US_ASCII)) {
            assertFalse(ByteScan.isTChar(b), "byte '" + (char) b + "' must not be a tchar");
        }
        assertFalse(ByteScan.isTChar((byte) 0));
        assertFalse(ByteScan.isTChar((byte) 127));
        assertFalse(ByteScan.isTChar((byte) -1)); // high-bit byte, e.g. UTF-8 continuation
    }

    // ── indexOf: SWAR vs scalar, every boundary ─────────────────────────────

    @Test
    void indexOf_swarAgreesWithScalar_everyLengthAndPosition() {
        Random rnd = new Random(42);
        for (int len = 0; len <= 256; len++) {
            byte[] buf = new byte[len];
            rnd.nextBytes(buf);
            // Ensure the target byte value (7) doesn't appear anywhere except where we plant it.
            for (int i = 0; i < len; i++) if (buf[i] == 7) buf[i] = 8;

            assertEquals(-1, ByteScan.indexOfScalar(buf, 0, len, (byte) 7));
            assertEquals(ByteScan.indexOfScalar(buf, 0, len, (byte) 7), ByteScan.indexOf(buf, 0, len, (byte) 7));

            for (int pos = 0; pos < len; pos++) {
                byte[] planted = buf.clone();
                planted[pos] = 7;
                int expected = ByteScan.indexOfScalar(planted, 0, len, (byte) 7);
                assertEquals(pos, expected, "scalar oracle disagrees with itself at pos " + pos);
                assertEquals(expected, ByteScan.indexOf(planted, 0, len, (byte) 7),
                        "SWAR disagrees with scalar at len=" + len + " pos=" + pos);
            }
        }
    }

    @Test
    void indexOf_unalignedStart_agreesWithScalar() {
        Random rnd = new Random(7);
        byte[] buf = new byte[64];
        rnd.nextBytes(buf);
        for (int i = 0; i < buf.length; i++) if (buf[i] == 9) buf[i] = 10;
        buf[40] = 9;
        for (int from = 0; from < 8; from++) {
            assertEquals(ByteScan.indexOfScalar(buf, from, buf.length, (byte) 9),
                    ByteScan.indexOf(buf, from, buf.length, (byte) 9));
        }
    }

    // ── indexOfCrLfCrLf: SWAR vs scalar, every boundary ─────────────────────

    @Test
    void indexOfCrLfCrLf_swarAgreesWithScalar_everyLengthAndPosition() {
        Random rnd = new Random(99);
        for (int len = 4; len <= 128; len++) {
            byte[] base = new byte[len];
            rnd.nextBytes(base);
            // Strip any accidental \r or \n so only the planted match exists.
            for (int i = 0; i < len; i++) {
                if (base[i] == '\r' || base[i] == '\n') base[i] = 'x';
            }
            assertEquals(-1, ByteScan.indexOfCrLfCrLfScalar(base, 0, len));
            assertEquals(-1, ByteScan.indexOfCrLfCrLf(base, 0, len));

            for (int pos = 0; pos <= len - 4; pos++) {
                byte[] planted = base.clone();
                planted[pos] = '\r'; planted[pos + 1] = '\n'; planted[pos + 2] = '\r'; planted[pos + 3] = '\n';
                int expected = ByteScan.indexOfCrLfCrLfScalar(planted, 0, len);
                assertEquals(pos, expected);
                assertEquals(expected, ByteScan.indexOfCrLfCrLf(planted, 0, len),
                        "SWAR disagrees with scalar at len=" + len + " pos=" + pos);
            }
        }
    }

    @Test
    void indexOfCrLfCrLf_matchAtVeryLastPossiblePosition() {
        byte[] buf = "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        int expected = buf.length - 4;
        assertEquals(expected, ByteScan.indexOfCrLfCrLf(buf, 0, buf.length));
    }

    @Test
    void indexOfCrLfCrLf_bareCrNotFollowedByLf_isNotAMatch() {
        byte[] buf = "a\r\rb\r\n\r\nc".getBytes(StandardCharsets.US_ASCII);
        int expected = ByteScan.indexOfCrLfCrLfScalar(buf, 0, buf.length);
        assertEquals(expected, ByteScan.indexOfCrLfCrLf(buf, 0, buf.length));
        assertTrue(expected >= 0);
    }

    @Test
    void indexOfCrLfCrLf_lengthNotMultipleOfEight_doesNotOverrun() {
        for (int len = 4; len <= 20; len++) {
            byte[] buf = new byte[len];
            for (int i = 0; i < len; i++) buf[i] = 'x';
            assertEquals(-1, ByteScan.indexOfCrLfCrLf(buf, 0, len));
            if (len >= 4) {
                buf[len - 4] = '\r'; buf[len - 3] = '\n'; buf[len - 2] = '\r'; buf[len - 1] = '\n';
                assertEquals(len - 4, ByteScan.indexOfCrLfCrLf(buf, 0, len));
            }
        }
    }

    // ── Case-insensitive comparison ─────────────────────────────────────────

    @Test
    void equalsIgnoreCaseAscii_array_matchesRegardlessOfCase() {
        byte[] buf = "Content-Type".getBytes(StandardCharsets.US_ASCII);
        assertTrue(ByteScan.equalsIgnoreCaseAscii(buf, 0, buf.length, "content-type"));
        assertTrue(ByteScan.equalsIgnoreCaseAscii(buf, 0, buf.length, "CONTENT-TYPE"));
        assertFalse(ByteScan.equalsIgnoreCaseAscii(buf, 0, buf.length, "content-length"));
    }

    @Test
    void equalsIgnoreCaseAscii_twoArrays() {
        byte[] a = "Accept".getBytes(StandardCharsets.US_ASCII);
        byte[] b = "aCCEPT".getBytes(StandardCharsets.US_ASCII);
        assertTrue(ByteScan.equalsIgnoreCaseAscii(a, 0, a.length, b, 0, b.length));
        byte[] c = "Accept-X".getBytes(StandardCharsets.US_ASCII);
        assertFalse(ByteScan.equalsIgnoreCaseAscii(a, 0, a.length, c, 0, c.length));
    }

    @Test
    void equalsIgnoreCase_view() {
        ArrayView v = new ArrayView("Keep-Alive");
        assertTrue(ByteScan.equalsIgnoreCase(v, 0, v.length(), "keep-alive"));
        assertFalse(ByteScan.equalsIgnoreCase(v, 0, v.length(), "close"));
    }

    // ── Token lists ──────────────────────────────────────────────────────────

    @Test
    void tokenListContains_findsTokenAmongMultiple() {
        ArrayView v = new ArrayView("keep-alive, Upgrade");
        assertTrue(ByteScan.tokenListContains(v, "upgrade"));
        assertTrue(ByteScan.tokenListContains(v, "keep-alive"));
        assertFalse(ByteScan.tokenListContains(v, "close"));
    }

    @Test
    void tokenListContains_singleToken() {
        ArrayView v = new ArrayView("close");
        assertTrue(ByteScan.tokenListContains(v, "close"));
    }

    @Test
    void tokenListContains_emptyList() {
        ArrayView v = new ArrayView("");
        assertFalse(ByteScan.tokenListContains(v, "close"));
    }

    // ── Header-name hash ─────────────────────────────────────────────────────

    @Test
    void hashNameIgnoreCaseAscii_isCaseInsensitive() {
        byte[] lower = "content-length".getBytes(StandardCharsets.US_ASCII);
        byte[] mixed = "Content-Length".getBytes(StandardCharsets.US_ASCII);
        byte[] upper = "CONTENT-LENGTH".getBytes(StandardCharsets.US_ASCII);
        int h1 = ByteScan.hashNameIgnoreCaseAscii(lower, 0, lower.length);
        int h2 = ByteScan.hashNameIgnoreCaseAscii(mixed, 0, mixed.length);
        int h3 = ByteScan.hashNameIgnoreCaseAscii(upper, 0, upper.length);
        assertEquals(h1, h2);
        assertEquals(h2, h3);
    }

    @Test
    void hashNameIgnoreCaseAscii_stringOverloadAgreesWithByteArrayOverload() {
        for (String name : new String[]{"content-length", "Content-Length", "CONTENT-LENGTH", "x", ""}) {
            byte[] b = name.getBytes(StandardCharsets.US_ASCII);
            assertEquals(ByteScan.hashNameIgnoreCaseAscii(b, 0, b.length), ByteScan.hashNameIgnoreCaseAscii(name));
        }
    }

    @Test
    void hashNameIgnoreCaseAscii_differentNamesUsuallyDiffer() {
        String[] names = {"content-length", "content-type", "authorization", "cookie", "accept",
                "host", "user-agent", "x-forwarded-for", "connection", "upgrade"};
        java.util.Set<Integer> hashes = new java.util.HashSet<>();
        for (String n : names) {
            byte[] b = n.getBytes(StandardCharsets.US_ASCII);
            hashes.add(ByteScan.hashNameIgnoreCaseAscii(b, 0, b.length));
        }
        assertEquals(names.length, hashes.size(), "expected no collisions among common header names");
    }

    // ── Decimal / hex parsing ────────────────────────────────────────────────

    @Test
    void parseDecimalStrict_validAndInvalidCases() {
        assertEquals(0L, parse("0"));
        assertEquals(12345L, parse("12345"));
        assertEquals(Long.MAX_VALUE, parse(Long.toString(Long.MAX_VALUE)));
        assertEquals(ByteScan.PARSE_INVALID, parse(""));
        assertEquals(ByteScan.PARSE_INVALID, parse("12a45"));
        assertEquals(ByteScan.PARSE_INVALID, parse("-1"));
        assertEquals(ByteScan.PARSE_INVALID, parse("+1"));
        assertEquals(ByteScan.PARSE_INVALID, parse("99999999999999999999")); // overflow
        assertEquals(ByteScan.PARSE_INVALID, parse("10000000000000000000")); // > Long.MAX_VALUE, 20 digits already rejected by length
    }

    private static long parse(String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        return ByteScan.parseDecimalStrict(b, 0, b.length);
    }

    @Test
    void parseHexStrict_validAndInvalidCases() {
        assertEquals(0xFFL, hex("ff", 8));
        assertEquals(0xABCDL, hex("aBcD", 8));
        assertEquals(ByteScan.PARSE_INVALID, hex("", 8));
        assertEquals(ByteScan.PARSE_INVALID, hex("xyz", 8));
        assertEquals(ByteScan.PARSE_INVALID, hex("123456789", 8)); // too many digits
    }

    private static long hex(String s, int maxDigits) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        return ByteScan.parseHexStrict(b, 0, b.length, maxDigits);
    }
}
