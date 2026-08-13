package dev.relism.flash.models;

import dev.relism.flash.routing.routers.fastpathrouter.FastPathViews;
import dev.relism.fpr.core.ByteView;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * must produce byte-for-byte identical results to the percent-decoding slow path it bypasses —
 * verified here across clean values, values needing every kind of decoding, and the boundary
 */
class QueryParamsFastPathTest {

    private static QueryParams of(String query) {
        byte[] bytes = query.getBytes(StandardCharsets.US_ASCII);
        return new QueryParams(new FastPathViews.RequestByteView(bytes, 0, bytes.length));
    }

    @Test
    void cleanValue_noPercentOrPlus_decodesToItself() {
        QueryParams qp = of("name=hello&city=NewYork");
        assertEquals("hello", qp.get("name"));
        assertEquals("NewYork", qp.get("city"));
    }

    @Test
    void valueWithPlus_decodesToSpace_takesSlowPath() {
        QueryParams qp = of("q=hello+world");
        assertEquals("hello world", qp.get("q"));
    }

    @Test
    void valueWithPercentEscape_decodesCorrectly_takesSlowPath() {
        QueryParams qp = of("q=hello%20world");
        assertEquals("hello world", qp.get("q"));
    }

    @Test
    void valueWithInvalidPercentEscape_keepsLiteralPercent() {
        QueryParams qp = of("q=100%25off");
        assertEquals("100%off", qp.get("q"));
        QueryParams qp2 = of("q=trailing%2");
        assertEquals("trailing%2", qp2.get("q"));
    }

    @Test
    void emptyValue_isClean_decodesToEmptyString() {
        QueryParams qp = of("a=&b=1");
        assertEquals("", qp.get("a"));
        assertEquals("1", qp.get("b"));
    }

    @Test
    void mixedCleanAndEncodedValues_inSameQueryString() {
        QueryParams qp = of("clean=abc&encoded=a%20b&plussed=a+b");
        assertEquals("abc", qp.get("clean"));
        assertEquals("a b", qp.get("encoded"));
        assertEquals("a b", qp.get("plussed"));
    }


    @Test
    void view_returnsRawUndecodedBytes() {
        QueryParams qp = of("q=a+b");
        ByteView v = qp.view("q");
        assertNotNull(v);
        assertEquals(3, v.length());
        assertEquals('+', (char) v.byteAt(1)); // raw, not percent/plus-decoded
    }

    @Test
    void view_missingKey_returnsNull() {
        QueryParams qp = of("q=1");
        assertNull(qp.view("missing"));
    }

    @Test
    void view_poolWraparound_aliasesAnEarlierReturnedView() {
        QueryParams qp = of("a=1&b=2&c=3&d=4&e=5");
        ByteView v1 = qp.view("a");
        qp.view("b");
        qp.view("c");
        qp.view("d"); // pool size 4 — not wrapped yet
        assertEquals('1', v1.byteAt(0));
        ByteView v5 = qp.view("e"); // 5th call wraps back to v1's slot
        assertSame(v1, v5);
        assertEquals('5', v1.byteAt(0));
    }
}
