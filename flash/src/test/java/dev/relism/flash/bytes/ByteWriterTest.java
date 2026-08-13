package dev.relism.flash.bytes;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ByteWriterTest {

    private static String asString(ByteWriter w) {
        return new String(w.array(), 0, w.length(), StandardCharsets.US_ASCII);
    }

    @Test
    void writeByte_and_writeBytes() {
        ByteWriter w = new ByteWriter(4);
        w.writeByte((byte) 'H');
        w.writeBytes("ello".getBytes(StandardCharsets.US_ASCII));
        assertEquals("Hello", asString(w));
    }

    @Test
    void writeBytes_offsetAndLength() {
        ByteWriter w = new ByteWriter(4);
        byte[] src = "xxHELLOxx".getBytes(StandardCharsets.US_ASCII);
        w.writeBytes(src, 2, 5);
        assertEquals("HELLO", asString(w));
    }

    @Test
    void growsPastInitialCapacity_withoutLosingData() {
        ByteWriter w = new ByteWriter(2);
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            w.writeByte((byte) ('a' + (i % 26)));
            expected.append((char) ('a' + (i % 26)));
        }
        assertEquals(expected.toString(), asString(w));
    }

    @Test
    void reset_reusesBufferFromScratch() {
        ByteWriter w = new ByteWriter(16);
        w.writeBytes("first".getBytes(StandardCharsets.US_ASCII));
        byte[] bufBeforeReset = w.array();
        w.reset();
        assertEquals(0, w.length());
        w.writeBytes("second".getBytes(StandardCharsets.US_ASCII));
        assertEquals("second", asString(w));
        assertSame(bufBeforeReset, w.array(), "reset() must not reallocate when capacity already suffices");
    }

    @Test
    void writeDecimal_variousValues() {
        assertDecimal("0", 0);
        assertDecimal("7", 7);
        assertDecimal("42", 42);
        assertDecimal("1000000", 1_000_000);
        assertDecimal(Long.toString(Long.MAX_VALUE), Long.MAX_VALUE);
    }

    private static void assertDecimal(String expected, long value) {
        ByteWriter w = new ByteWriter(4);
        w.writeDecimal(value);
        assertEquals(expected, asString(w));
    }

    @Test
    void writeDecimal_rejectsNegative() {
        ByteWriter w = new ByteWriter(4);
        assertThrows(IllegalArgumentException.class, () -> w.writeDecimal(-1));
    }

    @Test
    void writeHex_variousValues() {
        assertHex("0", 0);
        assertHex("ff", 0xFF);
        assertHex("1a2b3c", 0x1A2B3C);
        assertHex("ffffffff", 0xFFFFFFFF);
    }

    private static void assertHex(String expected, int value) {
        ByteWriter w = new ByteWriter(4);
        w.writeHex(value);
        assertEquals(expected, asString(w));
    }

    @Test
    void writeAsciiLower_lowersUppercaseOnly() {
        ByteWriter w = new ByteWriter(4);
        w.writeAsciiLower("Content-TYPE");
        assertEquals("content-type", asString(w));
    }

    @Test
    void writeAscii_preservesCase() {
        ByteWriter w = new ByteWriter(4);
        w.writeAscii("Content-TYPE");
        assertEquals("Content-TYPE", asString(w));
    }

    @Test
    void writeUInt16_bigEndian() {
        ByteWriter w = new ByteWriter(4);
        w.writeUInt16(0x1234);
        assertArrayEquals(new byte[]{0x12, 0x34}, java.util.Arrays.copyOf(w.array(), w.length()));
    }

    @Test
    void writeUInt24_bigEndian() {
        ByteWriter w = new ByteWriter(4);
        w.writeUInt24(0x123456);
        assertArrayEquals(new byte[]{0x12, 0x34, 0x56}, java.util.Arrays.copyOf(w.array(), w.length()));
    }

    @Test
    void writeUInt31_masksTopBit() {
        ByteWriter w = new ByteWriter(4);
        w.writeUInt31(0xFFFFFFFF); // all bits set -> top bit must be cleared
        assertArrayEquals(new byte[]{0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                java.util.Arrays.copyOf(w.array(), w.length()));
    }

    @Test
    void writeUInt32_bigEndian() {
        ByteWriter w = new ByteWriter(4);
        w.writeUInt32(0x01020304);
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04}, java.util.Arrays.copyOf(w.array(), w.length()));
    }
}
