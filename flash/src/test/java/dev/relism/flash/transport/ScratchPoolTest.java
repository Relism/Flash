package dev.relism.flash.transport;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScratchPoolTest {

    @Test
    void acquire_withEmptyPool_returnsFreshInstance() {
        ScratchPool pool = new ScratchPool();
        ConnectionScratch scratch = pool.acquire();
        assertNotNull(scratch);
        assertNotNull(scratch.sha1);
        assertEquals(ConnectionScratch.RELAY_BUFFER_SIZE, scratch.relayBuffer.length);
        assertNotNull(scratch.responseHead);
        assertEquals(0, scratch.responseHead.length());
    }

    @Test
    void release_thenAcquire_reusesTheSameInstance() {
        ScratchPool pool = new ScratchPool();
        ConnectionScratch first = pool.acquire();
        pool.release(first);
        ConnectionScratch second = pool.acquire();
        assertSame(first, second);
    }

    @Test
    void bound_isRespected_excessReleasesAreDropped() {
        ScratchPool pool = new ScratchPool(2);
        ConnectionScratch a = pool.acquire();
        ConnectionScratch b = pool.acquire();
        ConnectionScratch c = pool.acquire();
        pool.release(a);
        pool.release(b);
        pool.release(c); // pool already has 2 -- this one is dropped, not queued

        Set<ConnectionScratch> reacquired = new HashSet<>();
        reacquired.add(pool.acquire());
        reacquired.add(pool.acquire());
        ConnectionScratch third = pool.acquire(); // freshly allocated, pool was exhausted at 2
        assertFalse(reacquired.contains(third));
        assertEquals(2, reacquired.size());
    }

    @Test
    void reset_clearsTheMessageDigestState() {
        // A dirty digest (mid-update, not yet digested) must not leak into the next connection
        // that reuses this scratch -- the classic cross-connection-leak hazard for pooled state.
        ScratchPool pool = new ScratchPool();
        ConnectionScratch scratch = pool.acquire();
        scratch.sha1.update((byte) 'x');
        pool.release(scratch);

        ConnectionScratch reused = pool.acquire();
        assertSame(scratch, reused);
        // If reset() had not run, digesting an empty input now would still reflect the earlier
        // update. A byte array is not the actual assertion here (MessageDigest doesn't expose
        // "reset happened") - the practical proof is that digest() with no further updates
        // matches the well-known empty-input SHA-1 digest.
        byte[] emptyDigest = reused.sha1.digest();
        byte[] expected = {
                (byte) 0xda, 0x39, (byte) 0xa3, (byte) 0xee, 0x5e, 0x6b, 0x4b, 0x0d,
                0x32, 0x55, (byte) 0xbf, (byte) 0xef, (byte) 0x95, 0x60, 0x18, (byte) 0x90,
                (byte) 0xaf, (byte) 0xd8, 0x07, 0x09
        };
        assertArrayEquals(expected, emptyDigest);
    }
}
