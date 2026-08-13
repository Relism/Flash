package dev.relism.flash.h2.frame;

import dev.relism.flash.h2.Http2Exception;
import dev.relism.flash.transport.BufferedByteSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 5's DoD: "Fuzz test green for 10 million random inputs." Throws fully random bytes at
 * {@link Http2FrameReader} and asserts that only a typed, expected outcome ever results: a
 * {@link Http2Exception} (a declared length exceeding {@code MAX_FRAME_SIZE_LOCAL} — the
 * overwhelmingly common outcome, since a random 24-bit length is astronomically likely to
 * exceed 16384), an {@link EOFException} (the random input ran out before a full frame arrived
 * — the second most common outcome, since fuzz inputs are deliberately small), or a
 * {@link SocketTimeoutException} (never actually expected here — no deadline is short enough to
 * trip against an in-memory stream — but a legal outcome of the API's own contract). Anything
 * else escaping — {@code ArrayIndexOutOfBoundsException}, {@code NegativeArraySizeException},
 * {@code OutOfMemoryError}, or simply never returning — fails the test.
 */
class Http2FrameReaderFuzzTest {

    private static final int TRIALS = 10_000_000;
    private static final int MAX_INPUT_LEN = 64;

    @Test
    void fuzz_10MillionRandomInputs_onlyTypedOutcomesEscape() {
        Random rnd = new Random(0x4855_3244_5F46_5A32L);
        byte[] data = new byte[MAX_INPUT_LEN];

        for (int trial = 0; trial < TRIALS; trial++) {
            int len = rnd.nextInt(MAX_INPUT_LEN + 1);
            for (int i = 0; i < len; i++) data[i] = (byte) rnd.nextInt(256);

            BufferedByteSource src = new BufferedByteSource(
                    new ByteArrayInputStream(data, 0, len), null, 128);
            Http2FrameReader reader = new Http2FrameReader(src, 128);

            try {
                FrameHeader header = reader.readFrame();
                if (header != null) {
                    reader.consumeFrame();
                }
            } catch (Http2Exception | EOFException | SocketTimeoutException expected) {
                // any of these three is a correctly-typed rejection of malformed/truncated input
            } catch (IOException e) {
                fail("unexpected IOException at trial " + trial + " (len=" + len + "): " + e, e);
            } catch (RuntimeException e) {
                fail("unexpected RuntimeException at trial " + trial + " (len=" + len + "): " + e, e);
            }
        }
    }
}
