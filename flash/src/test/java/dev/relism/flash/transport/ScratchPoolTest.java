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
        assertNotNull(scratch.responseHead);
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
}
