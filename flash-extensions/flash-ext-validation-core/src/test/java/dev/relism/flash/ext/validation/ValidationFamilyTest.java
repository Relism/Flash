package dev.relism.flash.ext.validation;

import dev.relism.flash.App;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationFamilyTest {

    /** What a codec's module defines for values the generic engines cannot check. */
    interface Records extends Validation {}

    static final class Engine implements Records {
        @Override public void verify(Object value) {}
    }

    static final class Other implements Records {
        @Override public void verify(Object value) {}
    }

    static final class Generic implements Validation {
        @Override public void verify(Object value) {}
    }

    @Test
    void noEngineOfTheFamilyIsEmpty() {
        FlashTest app = FlashTest.of(() -> App.create().add(Validation.class, new Generic()));

        assertTrue(Validation.family(app.services(), Records.class).isEmpty(), "a generic engine is not the family's");
    }

    @Test
    void theEngineAddedUnderTheFamilyIsTheOneFound() {
        FlashTest app = FlashTest.of(() -> App.create().add(Validation.class, new Generic()).add(Records.class, new Engine()));

        assertInstanceOf(Engine.class, Validation.family(app.services(), Records.class).orElseThrow());
    }

    @Test
    void anEngineOfTheFamilyAddedAsPlainValidationIsRefusedNamingIt() {
        FlashTest app = FlashTest.of(() -> App.create().add(Validation.class, new Engine()));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Validation.family(app.services(), Records.class));

        assertTrue(refused.getMessage().contains("Engine") && refused.getMessage().contains("Records"), refused.getMessage());
    }

    @Test
    void twoEnginesForTheFamilyAreRefusedNamingBoth() {
        FlashTest app = FlashTest.of(() -> App.create().add(Records.class, new Engine()).add(Records.class, new Other()));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Validation.family(app.services(), Records.class));

        assertTrue(refused.getMessage().contains("Engine") && refused.getMessage().contains("Other"), refused.getMessage());
    }
}
