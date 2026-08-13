package dev.relism.flash.bytes;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SlicePoolTest {

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    @Test
    void acquire_repositionsAndReturnsRequestedRange() {
        SlicePool pool = new SlicePool(4);
        byte[] buf = bytes("hello world");
        PooledSlice slice = pool.acquire(buf, 6, 5);
        assertEquals(5, slice.length());
        assertEquals('w', (char) slice.byteAt(0));
        assertEquals('d', (char) slice.byteAt(4));
    }

    @Test
    void acquire_withinPoolSize_returnsDistinctLiveSlices() {
        SlicePool pool = new SlicePool(4);
        byte[] buf = bytes("abcdefgh");
        PooledSlice a = pool.acquire(buf, 0, 1); // 'a'
        PooledSlice b = pool.acquire(buf, 1, 1); // 'b'
        PooledSlice c = pool.acquire(buf, 2, 1); // 'c'
        // All three still valid simultaneously — the pool hasn't wrapped yet (size 4).
        assertEquals('a', (char) a.byteAt(0));
        assertEquals('b', (char) b.byteAt(0));
        assertEquals('c', (char) c.byteAt(0));
    }

    @Test
    void wraparoundAliasesThePreviouslyReturnedSlice() {
        // Demonstrates the documented hazard: retaining a slice past `size` further acquire()
        // calls observes it silently repositioned to unrelated data.
        SlicePool pool = new SlicePool(2);
        byte[] buf = bytes("AABB");
        PooledSlice first = pool.acquire(buf, 0, 2); // "AA"
        assertEquals('A', (char) first.byteAt(0));

        pool.acquire(buf, 2, 2); // "BB" — slot 2, pool size 2 so this is still a fresh slot
        PooledSlice thirdCall = pool.acquire(buf, 2, 2); // wraps back to `first`'s slot
        assertSame(first, thirdCall, "pool of size 2 must reuse the first slot on the 3rd acquire()");
        // `first` is now silently "BB", not "AA" — the documented lifetime contract in action.
        assertEquals('B', (char) first.byteAt(0));
    }

    @Test
    void constructor_rejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> new SlicePool(0));
        assertThrows(IllegalArgumentException.class, () -> new SlicePool(-1));
    }

    @Test
    void size_reportsConstructedCapacity() {
        assertEquals(4, new SlicePool(4).size());
    }
}
