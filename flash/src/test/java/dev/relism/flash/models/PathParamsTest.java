package dev.relism.flash.models;

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
}
