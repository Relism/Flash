package dev.relism.flash.extension;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The four seams {@code flash-testing} is built on: reading back an ephemeral port,
 * replacing a binding, applying an application, and closing services at stop.
 */
class FlashAppLifecycleTest {

    private static FlashConfiguration ephemeral() {
        return FlashConfiguration.builder().port(0).host("127.0.0.1")
                .shutdownDrainTimeoutMs(250).build();
    }

    // ── port() ───────────────────────────────────────────────────────────────

    @Test
    void exposesTheOsAssignedPortBeforeStart() {
        FlashApp app = FlashApp.create(ephemeral());
        try {
            assertTrue(app.port() > 0, "port 0 should resolve to a real bound port");
            assertEquals(List.of(app.port()), app.ports());
        } finally {
            app.stop().join();
        }
    }

    @Test
    void keepsTheSamePortAcrossStart() {
        FlashApp app = FlashApp.create(ephemeral()).get("/ping", (req, res) -> "pong");
        try {
            int beforeStart = app.port();
            app.start();
            assertEquals(beforeStart, app.port());
        } finally {
            app.stop().join();
        }
    }

    // ── override() ───────────────────────────────────────────────────────────

    @Test
    void overrideReplacesAnExistingBinding() {
        FlashContext ctx = new FlashContext();
        ctx.provide(Greeter.class, () -> "real");
        ctx.override(Greeter.class, () -> "fake");

        ctx.resolveAll();

        assertEquals("fake", ctx.require(Greeter.class).greet());
    }

    @Test
    void overrideAlsoDeclaresWhenNothingWasBound() {
        FlashContext ctx = new FlashContext();
        ctx.override(Greeter.class, () -> "fake");

        ctx.resolveAll();

        assertEquals("fake", ctx.require(Greeter.class).greet());
    }

    @Test
    void overrideIsRejectedOnceDeclarationsAreClosed() {
        FlashContext ctx = new FlashContext();
        ctx.resolveAll();

        assertThrows(IllegalStateException.class, () -> ctx.override(Greeter.class, () -> "fake"));
    }

    @Test
    void lastInstalledExtensionWinsOverAnEarlierProvider() {
        FlashApp app = FlashApp.create(ephemeral())
                .install((registrar, ctx) -> ctx.provide(Greeter.class, () -> "real"))
                .install((registrar, ctx) -> ctx.override(Greeter.class, () -> "fake"));
        try {
            app.start();
            assertEquals("fake", app.ctx().require(Greeter.class).greet());
        } finally {
            app.stop().join();
        }
    }

    // ── apply() ──────────────────────────────────────────────────────────────

    @Test
    void applyRunsImmediatelyWithAnOpenContextAndABoundPort() {
        List<Integer> portSeenInsideConfigure = new ArrayList<>();

        FlashApp app = FlashApp.create(ephemeral()).apply(configured -> {
            portSeenInsideConfigure.add(configured.port());
            configured.ctx().provide(Greeter.class, () -> "from-application");
            configured.get("/hello", (req, res) -> "hi");
        });
        try {
            assertEquals(List.of(app.port()), portSeenInsideConfigure);
            app.start();
            assertEquals("from-application", app.ctx().require(Greeter.class).greet());
        } finally {
            app.stop().join();
        }
    }

    // ── onClose() ────────────────────────────────────────────────────────────

    @Test
    void stopRunsCloseCallbacksInReverseOrder() {
        List<String> closed = new ArrayList<>();
        FlashApp app = FlashApp.create(ephemeral()).apply(configured -> {
            configured.ctx().onClose(() -> closed.add("first"));
            configured.ctx().onClose(() -> closed.add("second"));
        });

        app.start();
        app.stop().join();

        assertEquals(List.of("second", "first"), closed);
    }

    @Test
    void aFactoryCanRegisterItsOwnTeardownWhileResolving() {
        AtomicInteger closes = new AtomicInteger();
        FlashApp app = FlashApp.create(ephemeral()).apply(configured ->
                configured.ctx().supply(Greeter.class, services -> {
                    services.onClose(closes::incrementAndGet);
                    return () -> "resolved";
                }));

        app.start();
        assertEquals(0, closes.get(), "must not close while the app is live");

        app.stop().join();
        assertEquals(1, closes.get());
    }

    @Test
    void aThrowingCloseCallbackDoesNotStopTheRest() {
        List<String> closed = new ArrayList<>();
        FlashApp app = FlashApp.create(ephemeral()).apply(configured -> {
            configured.ctx().onClose(() -> closed.add("ran"));
            configured.ctx().onClose(() -> { throw new IllegalStateException("boom"); });
        });

        app.start();
        assertDoesNotThrow(() -> app.stop().join());

        assertEquals(List.of("ran"), closed);
    }

    @Test
    void closeCallbacksRunAtMostOnce() {
        AtomicInteger closes = new AtomicInteger();
        FlashApp app = FlashApp.create(ephemeral())
                .apply(configured -> configured.ctx().onClose(closes::incrementAndGet));

        app.start();
        app.stop().join();
        app.stop().join();

        assertEquals(1, closes.get());
    }

    @FunctionalInterface
    interface Greeter { String greet(); }
}
