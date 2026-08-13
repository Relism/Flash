package dev.relism.flash.bytes;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SegmentedByteViewTest {

    @Test
    void reset_presentsSegmentsAsOneLogicalSequence() {
        byte[][] segments = {
                "Hello, ".getBytes(StandardCharsets.US_ASCII),
                "World".getBytes(StandardCharsets.US_ASCII),
                "!".getBytes(StandardCharsets.US_ASCII),
        };
        int[] offsets = {0, 0, 0};
        int[] lengths = {segments[0].length, segments[1].length, segments[2].length};

        SegmentedByteView view = new SegmentedByteView();
        view.reset(segments, offsets, lengths, 3);

        assertEquals(13, view.length());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < view.length(); i++) sb.append((char) view.byteAt(i));
        assertEquals("Hello, World!", sb.toString());
    }

    @Test
    void reset_honorsPerSegmentOffsetAndLength() {
        byte[][] segments = { "xxABCxx".getBytes(StandardCharsets.US_ASCII) };
        SegmentedByteView view = new SegmentedByteView();
        view.reset(segments, new int[]{2}, new int[]{3}, 1);
        assertEquals(3, view.length());
        assertEquals('A', (char) view.byteAt(0));
        assertEquals('C', (char) view.byteAt(2));
    }

    @Test
    void reset_isReusableAcrossCalls() {
        SegmentedByteView view = new SegmentedByteView();
        view.reset(new byte[][]{"abc".getBytes(StandardCharsets.US_ASCII)}, new int[]{0}, new int[]{3}, 1);
        assertEquals(3, view.length());
        view.reset(new byte[][]{"de".getBytes(StandardCharsets.US_ASCII)}, new int[]{0}, new int[]{2}, 1);
        assertEquals(2, view.length());
        assertEquals('d', (char) view.byteAt(0));
    }

    @Test
    void byteAt_outOfBoundsThrows() {
        SegmentedByteView view = new SegmentedByteView();
        view.reset(new byte[][]{"ab".getBytes(StandardCharsets.US_ASCII)}, new int[]{0}, new int[]{2}, 1);
        assertThrows(IndexOutOfBoundsException.class, () -> view.byteAt(2));
        assertThrows(IndexOutOfBoundsException.class, () -> view.byteAt(-1));
    }

    @Test
    void supportsLong_alwaysFalse() {
        SegmentedByteView view = new SegmentedByteView();
        view.reset(new byte[][]{"12345678".getBytes(StandardCharsets.US_ASCII)}, new int[]{0}, new int[]{8}, 1);
        assertFalse(view.supportsLong());
    }

    @Test
    void emptySegmentCount_isZeroLength() {
        SegmentedByteView view = new SegmentedByteView();
        view.reset(new byte[][]{}, new int[]{}, new int[]{}, 0);
        assertEquals(0, view.length());
    }
}
