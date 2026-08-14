package dev.relism.flash.bytes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PairsTest {

    @Test
    void packAndUnpack_roundTrip() {
        long p = Pairs.pack(1234, 5678);
        assertEquals(1234, Pairs.hi(p));
        assertEquals(5678, Pairs.lo(p));
    }

    @Test
    void packAndUnpack_zero() {
        long p = Pairs.pack(0, 0);
        assertEquals(0, Pairs.hi(p));
        assertEquals(0, Pairs.lo(p));
    }

    @Test
    void packAndUnpack_maxInts() {
        long p = Pairs.pack(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, Pairs.hi(p));
        assertEquals(Integer.MAX_VALUE, Pairs.lo(p));
    }

    @Test
    void lo_doesNotSignExtendFromHi() {
        // hi negative-looking bit pattern must not bleed into lo after unpack.
        long p = Pairs.pack(-1, 42);
        assertEquals(-1, Pairs.hi(p));
        assertEquals(42, Pairs.lo(p));
    }
}
