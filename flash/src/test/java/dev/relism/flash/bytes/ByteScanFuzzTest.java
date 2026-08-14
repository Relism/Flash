package dev.relism.flash.bytes;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Randomized agreement testing for {@link ByteScan}'s SWAR methods against their scalar
 * every length 0..256 ... including unaligned starts"). {@link ByteScanTest} already covers
 * every exact boundary deterministically; this class instead throws a large volume of fully
 * random bytes and random sub-ranges at both implementations, on a fixed seed for reproducible
 * CI failures, purely to catch any interaction between random byte content and the SWAR bit
 * tricks that a hand-picked boundary test would miss.
 */
class ByteScanFuzzTest {

    private static final int TRIALS = 20_000;
    private static final int MAX_LEN = 300;

    @Test
    void indexOf_agreesWithScalar_onFullyRandomInputs() {
        Random rnd = new Random(1234567);
        for (int t = 0; t < TRIALS; t++) {
            int len = rnd.nextInt(MAX_LEN + 1);
            byte[] buf = new byte[len];
            rnd.nextBytes(buf);
            byte target = (byte) rnd.nextInt(256);
            int from = len == 0 ? 0 : rnd.nextInt(len + 1);
            int to = from == len ? len : from + rnd.nextInt(len - from + 1);

            int expected = ByteScan.indexOfScalar(buf, from, to, target);
            int actual = assertDoesNotThrow(() -> ByteScan.indexOf(buf, from, to, target));
            int trial = t;
            assertEquals(expected, actual,
                    () -> "mismatch at trial " + trial + ", len=" + len + ", from=" + from + ", to=" + to);
        }
    }

    @Test
    void indexOfCrLfCrLf_agreesWithScalar_onFullyRandomInputs() {
        Random rnd = new Random(9876543);
        for (int t = 0; t < TRIALS; t++) {
            int len = rnd.nextInt(MAX_LEN + 1);
            byte[] buf = new byte[len];
            rnd.nextBytes(buf);
            // Occasionally bias toward CR/LF bytes so real matches (and near-matches) show up,
            // not just "no CR anywhere" cases.
            if (rnd.nextInt(3) == 0) {
                for (int i = 0; i < len; i++) {
                    if (rnd.nextInt(4) == 0) buf[i] = rnd.nextBoolean() ? (byte) '\r' : (byte) '\n';
                }
            }
            int from = len == 0 ? 0 : rnd.nextInt(len + 1);
            int to = from == len ? len : from + rnd.nextInt(len - from + 1);

            int expected = ByteScan.indexOfCrLfCrLfScalar(buf, from, to);
            int actual = assertDoesNotThrow(() -> ByteScan.indexOfCrLfCrLf(buf, from, to));
            int trial = t;
            assertEquals(expected, actual,
                    () -> "mismatch at trial " + trial + ", len=" + len + ", from=" + from + ", to=" + to);
        }
    }
}
