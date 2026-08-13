package dev.relism.flash.routing.routers.fastpathrouter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class FastPathViewsTest {

    private static final byte[] SHARED_BUFFER = "GET /api/users?id=1 HTTP/1.1".getBytes(StandardCharsets.UTF_8);

    // --- RequestByteView ---

    @Test
    void requestByteView_readsCorrectSlice() {
        FastPathViews.RequestByteView view = new FastPathViews.RequestByteView(SHARED_BUFFER, 4, 10);
        assertEquals(10, view.length());
        assertEquals('/', view.byteAt(0));
        assertEquals('s', view.byteAt(9));
        assertEquals("/api/users", view.toString());
    }

    @Test
    void requestByteView_outOfBounds_throwsException() {
        FastPathViews.RequestByteView view = new FastPathViews.RequestByteView(SHARED_BUFFER, 4, 10);
        assertThrows(IndexOutOfBoundsException.class, () -> view.byteAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> view.byteAt(10));
    }

    // --- EX-42: reset() repositions the same instance, zero allocation ---------

    @Test
    void requestByteView_reset_repositionsSameInstance() {
        FastPathViews.RequestByteView view = new FastPathViews.RequestByteView(SHARED_BUFFER, 4, 10);
        assertEquals("/api/users", view.toString());

        byte[] other = "PUT /orders/9 HTTP/1.1".getBytes(StandardCharsets.UTF_8);
        view.reset(other, 4, 8);
        assertEquals(8, view.length());
        assertEquals("/orders/", view.toString());
    }

    @Test
    void requestByteView_reset_updatesArrayBackedByteViewAccessors() {
        FastPathViews.RequestByteView view = new FastPathViews.RequestByteView(SHARED_BUFFER, 0, 3);
        byte[] other = "zzHELLOzz".getBytes(StandardCharsets.UTF_8);
        view.reset(other, 2, 5);

        assertSame(other, view.array());
        assertEquals(2, view.offset());
        assertEquals(5, view.length());
        assertEquals("HELLO", view.toString());
    }

    // --- MethodPathByteView ---

    @Test
    void methodPathByteView_combinesViewsCorrectly() {
        byte[] methodBytes = "POST".getBytes(StandardCharsets.UTF_8);
        FastPathViews.RequestByteView pathView = new FastPathViews.RequestByteView(SHARED_BUFFER, 4, 10); // "/api/users"

        FastPathViews.MethodPathByteView composite = new FastPathViews.MethodPathByteView();
        composite.reset(methodBytes, pathView);

        assertEquals(14, composite.length());
        assertEquals('P', composite.byteAt(0));
        assertEquals('T', composite.byteAt(3));
        assertEquals('/', composite.byteAt(4));
        assertEquals('s', composite.byteAt(13));
    }

    // --- SocketByteView & StringByteView ---

    @Test
    void socketByteView_wrapsByteArray() {
        byte[] data = "Hello".getBytes(StandardCharsets.UTF_8);
        FastPathViews.SocketByteView view = new FastPathViews.SocketByteView(data);
        assertEquals(5, view.length());
        assertEquals('H', view.byteAt(0));
    }

    @Test
    void stringByteView_wrapsString() {
        FastPathViews.StringByteView view = new FastPathViews.StringByteView("Hello");
        assertEquals(5, view.length());
        assertEquals('o', view.byteAt(4));
    }
}
