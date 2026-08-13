package dev.relism.flash.models;

import dev.relism.flash.routing.routers.fastpathrouter.FastPathViews;
import dev.relism.fpr.core.ByteView;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PathParamsTest {

    // --- helpers ---

    private static PathParams of(String path, String... paramsPairs) {
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        ByteView view = new ByteView() {
            public int length() { return bytes.length; }
            public byte byteAt(int idx) { return bytes[idx]; }
        };
        
        String[] names = new String[paramsPairs.length / 2];
        int[] starts = new int[paramsPairs.length / 2];
        int[] lens = new int[paramsPairs.length / 2];
        
        for (int i = 0; i + 1 < paramsPairs.length; i += 2) {
            names[i / 2] = paramsPairs[i];
            String val = paramsPairs[i + 1];
            starts[i / 2] = path.indexOf(val);
            lens[i / 2] = val.length();
        }
        
        return new PathParams(view, names, starts, lens);
    }

    // --- get ---

    @Test
    void get_existingParam() {
        PathParams params = of("/users/123/posts/456", "userId", "123", "postId", "456");
        assertEquals("123", params.get("userId"));
        assertEquals("456", params.get("postId"));
    }

    @Test
    void get_missingParam_returnsNull() {
        PathParams params = of("/users/123", "userId", "123");
        assertNull(params.get("unknown"));
    }

    // --- view ---

    @Test
    void view_existingParamZeroCopy() {
        PathParams params = of("/users/123", "userId", "123");
        ByteView view = params.view("userId");
        assertNotNull(view);
        assertEquals(3, view.length());
        assertEquals('1', view.byteAt(0));
        assertEquals('3', view.byteAt(2));
    }

    @Test
    void view_missingParam_returnsNull() {
        PathParams params = of("/users/123", "userId", "123");
        assertNull(params.view("unknown"));
    }

    @Test
    void view_poolWraparound_aliasesAnEarlierReturnedView() {
        // EX-05's pooled path only engages when `source` is array-backed (ArrayBackedByteView) —
        // unlike of()'s plain inline ByteView (which exercises the non-pooled fallback, still
        // correct but not the code path this test targets), use the same view type RequestParser
        // actually produces.
        String path = "/a/1/b/2/c/3/d/4/e/5";
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        ByteView source = new FastPathViews.RequestByteView(bytes, 0, bytes.length);
        String[] names = {"a", "b", "c", "d", "e"};
        int[] starts = new int[names.length];
        int[] lens = new int[names.length];
        String[] values = {"1", "2", "3", "4", "5"};
        for (int i = 0; i < names.length; i++) {
            starts[i] = path.indexOf(values[i]);
            lens[i] = values[i].length();
        }
        PathParams params = new PathParams(source, names, starts, lens);

        ByteView v1 = params.view("a");
        params.view("b");
        params.view("c");
        params.view("d"); // pool size 4 — not wrapped yet
        assertEquals('1', v1.byteAt(0));
        ByteView v5 = params.view("e"); // 5th call wraps back to v1's slot
        assertSame(v1, v5);
        assertEquals('5', v1.byteAt(0));
    }
}
