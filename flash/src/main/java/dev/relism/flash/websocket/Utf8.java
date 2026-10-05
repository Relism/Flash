package dev.relism.flash.websocket;

/**
 * Incremental UTF-8 validation for the bytes of a text message (RFC 6455 §8.1), which arrive one
 * fragment at a time and must be judged without waiting for the rest.
 *
 * <p>A table-driven DFA (Björn Höhrmann's): one {@code int} of carried state, one array lookup per
 * byte, no decoding into characters and nothing allocated. {@link #ACCEPT} between two bytes means
 * every sequence so far is complete and well-formed; any other state means a sequence is still
 * open, which is an error at the end of a message but not in the middle of one.
 */
final class Utf8 {

    /** Every sequence seen so far is complete: the only state a message may end in. */
    static final int ACCEPT = 0;

    /** The bytes are not UTF-8, and no further byte can make them so. */
    static final int REJECT = 12;

    private Utf8() {}

    /**
     * 256 byte-to-class entries, then the class-by-state transitions. Values are all below 128, so
     * a {@code byte[]} holds them and the sign extension Java would apply never happens.
     */
    private static final byte[] TABLE = {
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1, 9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7, 7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        8,8,2,2,2,2,2,2,2,2,2,2,2,2,2,2, 2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,
        10,3,3,3,3,3,3,3,3,3,3,3,3,4,3,3, 11,6,6,6,5,8,8,8,8,8,8,8,8,8,8,8,

        0,12,24,36,60,96,84,12,12,12,48,72, 12,12,12,12,12,12,12,12,12,12,12,12,
        12,0,12,12,12,12,12,0,12,0,12,12, 12,24,12,12,12,12,12,24,12,24,12,12,
        12,12,12,12,12,12,12,24,12,12,12,12, 12,24,12,12,12,12,12,12,12,24,12,12,
        12,12,12,12,12,12,12,36,12,36,12,12, 12,36,12,12,12,12,12,36,12,36,12,12,
        12,36,12,12,12,12,12,12,12,12,12,12,
    };

    /**
     * Continues validation of {@code state} over {@code len} bytes at {@code off}, and returns the
     * state to carry into the next fragment. Stops at the first byte that cannot be UTF-8.
     */
    static int validate(int state, byte[] buf, int off, int len) {
        for (int i = off, end = off + len; i < end; i++) {
            state = TABLE[256 + state + TABLE[buf[i] & 0xFF]];
            if (state == REJECT) return REJECT;
        }
        return state;
    }
}
