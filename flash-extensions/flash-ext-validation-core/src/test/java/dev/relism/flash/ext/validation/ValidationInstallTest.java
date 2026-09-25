package dev.relism.flash.ext.validation;

import dev.relism.flash.extension.FlashContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationInstallTest {

    static final class First implements Validation {
        @Override public void verify(Object value) {}
    }

    static final class Second implements Validation {
        @Override public void verify(Object value) {}
    }

    @Test
    void oneEngineInstallsAndIsTheOneThatAnswers() {
        FlashContext ctx = new FlashContext();
        First engine = new First();

        assertDoesNotThrow(() -> Validation.install(ctx, engine));
        ctx.complete();

        assertTrue(ctx.require(Validation.class) == engine);
    }

    @Test
    void aSecondEngineFailsTheBootAndSaysWhichOne() {
        FlashContext ctx = new FlashContext();
        Validation.install(ctx, new First());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Validation.install(ctx, new Second()));

        assertTrue(refused.getMessage().contains("Second"), refused.getMessage());
    }
}
