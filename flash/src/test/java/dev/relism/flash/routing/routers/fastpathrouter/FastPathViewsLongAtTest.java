package dev.relism.flash.routing.routers.fastpathrouter;

import dev.relism.fpr.core.FastPathRouter;
import dev.relism.fpr.core.MatchResult;
import dev.relism.fpr.core.RouterBuilder;
import dev.relism.fpr.core.dsl.StringRouteParser;
import dev.relism.fpr.core.internal.runtime.ByteCompare;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code EX-04}: verifies the {@code longAt()}/{@code supportsLong()} contract against
 * {@code fpr-core}'s own word-at-a-time comparison code — not merely against a hand-derived
 * expectation, per the plan's explicit instruction to verify by testing against {@code fpr-core}
 * directly rather than by reading its bytecode (bytecode-reading only informed which byte order
 * to use; this test is the actual verification). A wrong endianness or a wrong bounds assumption
 * here produces silently mis-routed requests, the worst possible failure mode ({@code EX-04}'s
 * own registry entry) — so this covers both the raw word-read contract and an end-to-end router
 * match with the long path actually engaged.
 */
class FastPathViewsLongAtTest {

    // ── Raw longAt() vs. a hand-assembled little-endian expectation ────────

    @Test
    void longAt_assemblesLittleEndian() {
        byte[] buf = {1, 2, 3, 4, 5, 6, 7, 8};
        FastPathViews.RequestByteView view = new FastPathViews.RequestByteView(buf, 0, 8);
        assertTrue(view.supportsLong());
        long expected = 0x0807060504030201L; // byte 0 -> least significant byte
        assertEquals(expected, view.longAt(0));
    }

    @Test
    void longAt_respectsViewOffset_notJustArrayOffset() {
        byte[] buf = {(byte) 0xFF, (byte) 0xFF, 1, 2, 3, 4, 5, 6, 7, 8, (byte) 0xFF};
        FastPathViews.RequestByteView view = new FastPathViews.RequestByteView(buf, 2, 8);
        long expected = 0x0807060504030201L;
        assertEquals(expected, view.longAt(0));
    }

    // ── Cross-checked against fpr-core's own ByteCompare, the actual consumer of longAt() ──

    @Test
    void byteCompareEquals_agreesBetweenLongPathAndByteAtATimePath_onIdenticalContent() {
        byte[] content = "GET/users/1234567890/profile".getBytes(StandardCharsets.US_ASCII);
        FastPathViews.RequestByteView view = new FastPathViews.RequestByteView(content, 0, content.length);
        byte[] other = content.clone();

        assertTrue(ByteCompare.equals(view, 0, other, 0, content.length, true));
        assertTrue(ByteCompare.equals(view, 0, other, 0, content.length, false));
    }

    @Test
    void byteCompareEquals_agreesBetweenLongPathAndByteAtATimePath_onDivergingContent() {
        // Diverge at every position across an 8+-byte range, including inside a word, at a word
        // boundary, and in the scalar tail — a wrong longAt() would only show up at some of these.
        byte[] base = "abcdefghijklmnopqrstuvwxyz012345".getBytes(StandardCharsets.US_ASCII);
        FastPathViews.RequestByteView view = new FastPathViews.RequestByteView(base, 0, base.length);
        for (int diffAt = 0; diffAt < base.length; diffAt++) {
            byte[] other = base.clone();
            other[diffAt] = (byte) (other[diffAt] + 1);
            boolean withLong = ByteCompare.equals(view, 0, other, 0, base.length, true);
            boolean withoutLong = ByteCompare.equals(view, 0, other, 0, base.length, false);
            assertFalse(withLong, "long path failed to detect divergence at " + diffAt);
            assertEquals(withoutLong, withLong, "long/byte-at-a-time paths disagree at diffAt=" + diffAt);
        }
    }

    @Test
    void byteCompareIndexOf_agreesBetweenLongPathAndByteAtATimePath() {
        byte[] haystack = "xxxxxxxxxxxxxxxxxTARGETxxxxxxxxxxxxxxxxxx".getBytes(StandardCharsets.US_ASCII);
        byte[] needle = "TARGET".getBytes(StandardCharsets.US_ASCII);
        FastPathViews.RequestByteView view = new FastPathViews.RequestByteView(haystack, 0, haystack.length);

        int withLong = ByteCompare.indexOf(view, 0, haystack.length, needle, 0, needle.length, true);
        int withoutLong = ByteCompare.indexOf(view, 0, haystack.length, needle, 0, needle.length, false);
        assertEquals(withoutLong, withLong);
        assertTrue(withLong >= 0);
    }

    // ── End-to-end: a real router, literal routes >= 8 bytes, long path actually engaged ────

    @Test
    void router_matchesCorrectly_withLongLiteralSegmentsAndTheLongPathEnabled() {
        RouterBuilder<String> builder = new RouterBuilder<>();
        builder.add(StringRouteParser.parse("GET/aaaaaaaaaaaaaaaaaaaa"), "route-a");
        builder.add(StringRouteParser.parse("GET/bbbbbbbbbbbbbbbbbbbb"), "route-b");
        builder.add(StringRouteParser.parse("GET/aaaaaaaaaaaaaaaaaaab"), "route-a-near-miss");
        FastPathRouter<dev.relism.fpr.core.ByteView, String> router = builder.compile();

        assertEquals("route-a", matchOne(router, "GET", "/aaaaaaaaaaaaaaaaaaaa"));
        assertEquals("route-b", matchOne(router, "GET", "/bbbbbbbbbbbbbbbbbbbb"));
        // Differs only in the very last byte — must not be conflated with route-a by a
        // word-at-a-time comparison that got the tail handling wrong.
        assertEquals("route-a-near-miss", matchOne(router, "GET", "/aaaaaaaaaaaaaaaaaaab"));
        assertNull(matchOne(router, "GET", "/aaaaaaaaaaaaaaaaaaac"));
        assertNull(matchOne(router, "GET", "/ccccccccccccccccccccc"));
    }

    private static String matchOne(FastPathRouter<dev.relism.fpr.core.ByteView, String> router,
                                    String method, String path) {
        byte[] methodBytes = method.getBytes(StandardCharsets.US_ASCII);
        FastPathViews.RequestByteView pathView =
                new FastPathViews.RequestByteView(path.getBytes(StandardCharsets.US_ASCII), 0, path.length());
        FastPathViews.MethodPathByteView combined = new FastPathViews.MethodPathByteView();
        combined.reset(methodBytes, pathView);

        MatchResult<String> result = new MatchResult<>(8, 32);
        int labelId = router.match(combined, result);
        return labelId == FastPathRouter.NO_MATCH ? null : result.handler();
    }
}
