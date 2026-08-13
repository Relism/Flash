package dev.relism.flash.models;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code EX-09}: dedicated correctness coverage for {@link Http1HeaderMap}'s per-{@code reset()}
 * index — duplicate names, case variation, zero headers, and growth past the initial index
 * capacity up to {@code Http1Limits.MAX_HEADER_COUNT}. {@link HeaderMapTest} already covers the
 * ordinary lookup/forEach contract; this class targets the index machinery specifically.
 */
class Http1HeaderMapIndexTest {

    private static Http1HeaderMap parse(String... headers) {
        StringBuilder sb = new StringBuilder();
        for (String h : headers) sb.append(h).append("\r\n");
        byte[] buffer = sb.toString().getBytes(StandardCharsets.UTF_8);
        Http1HeaderMap map = new Http1HeaderMap();
        map.reset(buffer, 0, buffer.length);
        return map;
    }

    @Test
    void zeroHeaders_everyLookupIsEmpty() {
        Http1HeaderMap map = parse();
        assertNull(map.first("Host"));
        assertTrue(map.all("Host").isEmpty());
        assertTrue(map.all().isEmpty());
        assertNull(map.view("Host"));
        assertFalse(map.valueEqualsIgnoreCase("Connection", "close"));
    }

    @Test
    void duplicateHeaderNames_firstReturnsTheFirstOne_allReturnsAllInOrder() {
        Http1HeaderMap map = parse("X-Trace: a", "X-Trace: b", "X-Trace: c");
        assertEquals("a", map.first("X-Trace"));
        assertEquals(List.of("a", "b", "c"), map.all("X-Trace"));
    }

    @Test
    void caseVariation_indexHashAndCompareBothIgnoreCase() {
        Http1HeaderMap map = parse("X-Custom-Header: value1");
        assertEquals("value1", map.first("x-custom-header"));
        assertEquals("value1", map.first("X-CUSTOM-HEADER"));
        assertEquals("value1", map.first("X-cUsToM-hEaDeR"));
    }

    @Test
    void similarButDistinctNames_doNotCollideInTheIndex() {
        // Names sharing a hash-prefix-adjacent shape must still resolve independently.
        Http1HeaderMap map = parse("Accept: a", "Accept-Encoding: b", "Accept-Language: c");
        assertEquals("a", map.first("Accept"));
        assertEquals("b", map.first("Accept-Encoding"));
        assertEquals("c", map.first("Accept-Language"));
    }

    @Test
    void growsPastInitialIndexCapacity_upToMaxHeaderCount_andStaysCorrect() {
        int n = dev.relism.flash.http.Http1Limits.MAX_HEADER_COUNT;
        String[] headers = new String[n];
        for (int i = 0; i < n; i++) headers[i] = "X-Header-" + i + ": value-" + i;
        Http1HeaderMap map = parse(headers);

        assertEquals("value-0", map.first("X-Header-0"));
        assertEquals("value-" + (n - 1), map.first("X-Header-" + (n - 1)));
        assertEquals("value-" + (n / 2), map.first("X-Header-" + (n / 2)));
        assertEquals(n, map.all().size());
    }

    @Test
    void reset_rebuildsIndexFromScratch_noStaleEntriesFromPreviousRequest() {
        Http1HeaderMap map = parse("Host: first-request");
        assertEquals("first-request", map.first("Host"));
        assertNull(map.first("X-Only-In-Second"));

        byte[] second = "Host: second-request\r\nX-Only-In-Second: yes\r\n".getBytes(StandardCharsets.UTF_8);
        map.reset(second, 0, second.length);

        assertEquals("second-request", map.first("Host"));
        assertEquals("yes", map.first("X-Only-In-Second"));
    }

    @Test
    void repeatedResetsAcrossVaryingHeaderCounts_shrinkAndGrowSafely() {
        // A connection whose successive keep-alive requests have very different header counts
        // must never see stale entries from a larger previous request bleed into a smaller one.
        Http1HeaderMap map = new Http1HeaderMap();
        for (int round = 0; round < 5; round++) {
            int n = (round % 2 == 0) ? 20 : 2;
            String[] headers = new String[n];
            for (int i = 0; i < n; i++) headers[i] = "H" + i + ": v" + i + "-" + round;
            byte[] buf = String.join("\r\n", headers).concat("\r\n").getBytes(StandardCharsets.UTF_8);
            map.reset(buf, 0, buf.length);

            assertEquals(n, map.all().size(), "round " + round);
            assertEquals("v0-" + round, map.first("H0"));
            if (n < 20) assertNull(map.first("H19"), "round " + round + " must not see a stale H19");
        }
    }

    @Test
    void allocation_indexArraysAreNotReallocatedOnceWarm() {
        // The rigorous 0 B/op verification is the Phase 17 JMH gate (-prof gc); this is a
        // unit-test-level structural guarantee that repeated first()/all()/view() lookups never
        // re-trigger index growth (Arrays.copyOf inside ensureIndexCapacity) after the first
        // reset() has already sized the arrays for this header count — asserted by identity: the
        // backing array references must be the exact same objects before and after 100k lookups.
        Http1HeaderMap map = parse("A: 1", "B: 2", "C: 3", "D: 4");
        int[] namesBefore = arrayFieldValue(map, "nameOffsets");

        for (int i = 0; i < 100_000; i++) {
            assertEquals("2", map.first("B"));
            assertNotNull(map.view("C"));
            assertFalse(map.all("D").isEmpty());
        }

        int[] namesAfter = arrayFieldValue(map, "nameOffsets");
        assertSame(namesBefore, namesAfter, "lookups alone must never reallocate the index arrays");
    }

    @Test
    void view_poolWraparound_aliasesAnEarlierReturnedView() {
        // EX-05's documented hazard, demonstrated through the actual public API: Http1HeaderMap's
        // view() pool is sized 4 (VIEW_POOL_SIZE); a 5th call in the same request wraps around
        // and silently repositions the object the 1st call returned.
        dev.relism.fpr.core.ByteView v1 = null;
        Http1HeaderMap map = parse("A: 1", "B: 2", "C: 3", "D: 4", "E: 5");
        for (String name : new String[]{"A", "B", "C", "D"}) {
            dev.relism.fpr.core.ByteView v = map.view(name);
            if (v1 == null) v1 = v;
        }
        assertEquals('1', v1.byteAt(0)); // still "A"'s value — pool has not wrapped yet
        dev.relism.fpr.core.ByteView v5 = map.view("E"); // 5th call — wraps back to v1's slot
        assertSame(v1, v5, "the 5th view() call must reuse the 1st call's slice instance");
        assertEquals('5', v1.byteAt(0)); // v1 is now silently "E"'s value, not "A"'s
    }

    private static int[] arrayFieldValue(Http1HeaderMap map, String fieldName) {
        try {
            var field = Http1HeaderMap.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (int[]) field.get(map);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
