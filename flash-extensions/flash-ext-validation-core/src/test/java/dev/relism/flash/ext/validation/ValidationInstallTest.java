package dev.relism.flash.ext.validation;

import dev.relism.flash.App;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationInstallTest {

    static final class First implements Validation {
        @Override public void verify(Object value) {}
    }

    static final class Second implements Validation {
        @Override public void verify(Object value) {}
    }

    @RegisterExtension
    static FlashTest one = FlashTest.of(() -> App.create().add(Validation.class, new First()));

    @RegisterExtension
    static FlashTest two = FlashTest.of(() -> App.create().add(Validation.class, new First()).add(Validation.class, new Second()));

    @Test
    void oneEngineIsTheOneThatAnswers() {
        assertInstanceOf(First.class, one.services().get(Validation.class));
    }

    @Test
    void aSecondEngineFailsTheLookupNamingBoth() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> two.services().get(Validation.class));

        assertTrue(refused.getMessage().contains("First") && refused.getMessage().contains("Second"), refused.getMessage());
    }
}
