package dev.relism.flash.testing;

import dev.relism.flash.App;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A non-static field gets JUnit's method-level callbacks, so the app is rebuilt for every
 * test. Counting boots proves the isolation rather than inferring it from a fresh port, which
 * the OS is free to reuse.
 */
class FlashTestPerMethodScopeTest {

    private static final AtomicInteger boots = new AtomicInteger();

    @RegisterExtension
    FlashTest app = FlashTest.of(() -> {
        boots.incrementAndGet();
        return App.create().get("/ping", (req, res) -> "pong");
    });

    @AfterAll
    static void bootedOncePerTest() {
        assertEquals(2, boots.get(), "an instance FlashTest field should boot per test");
    }

    @Test
    void firstTestGetsItsOwnApp() {
        app.get("/ping").expectStatus(200).expectBody("pong");
    }

    @Test
    void secondTestGetsAnotherApp() {
        app.get("/ping").expectStatus(200).expectBody("pong");
    }
}
