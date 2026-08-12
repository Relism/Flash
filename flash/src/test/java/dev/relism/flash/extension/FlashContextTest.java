package dev.relism.flash.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlashContextTest {

    @Test
    void resolvesDeclaredGraphRegardlessOfDeclarationOrder() {
        FlashContext ctx = new FlashContext();
        ctx.supply(Service.class, Dependency.class, Service::new);
        ctx.provide(Dependency.class, new Dependency());

        ctx.resolveAll();

        assertNotNull(ctx.require(Service.class).dependency);
    }

    @Test
    void rejectsCircularDeclaredGraphBeforeReadyCallbacks() {
        FlashContext ctx = new FlashContext();
        ctx.supply(Left.class, services -> new Left(), Right.class);
        ctx.supply(Right.class, services -> new Right(), Left.class);

        IllegalStateException error = assertThrows(IllegalStateException.class, ctx::resolveAll);

        assertEquals("Circular service dependency: Left -> Right -> Left", error.getMessage());
    }

    @Test
    void rejectsAFactoryLookupThatWasNotDeclared() {
        FlashContext ctx = new FlashContext();
        ctx.provide(Dependency.class, new Dependency());
        ctx.supply(Service.class, services -> new Service(services.require(Dependency.class)));

        IllegalStateException error = assertThrows(IllegalStateException.class, ctx::resolveAll);

        assertTrue(error.getMessage().contains("undeclared dependency"));
    }

    private static final class Dependency {}
    private static final class Service { final Dependency dependency; Service(Dependency dependency) { this.dependency = dependency; } }
    private static final class Left {}
    private static final class Right {}
}
