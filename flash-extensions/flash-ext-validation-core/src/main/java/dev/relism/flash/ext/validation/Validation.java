package dev.relism.flash.ext.validation;

import dev.relism.flash.extension.FlashContext;

/**
 * Verifies that a value satisfies the rules its own type declares.
 *
 * <p>One method, because that is the whole of what an engine does. An implementation wraps a
 * validation library — {@code flash-ext-validation-jakarta}, {@code flash-ext-validation-avaje} —
 * and an application installs exactly one of them. What a rule means, which annotations exist and
 * how they are enforced is the engine's business; this module knows only when to ask.
 *
 * <p>A format module calls it on a body it has just read, so a request that breaks a rule is a 422
 * and never reaches a handler.
 */
public interface Validation {

    /**
     * Verifies {@code value} against its type's rules.
     *
     * @throws ValidationException 422, listing every rule the value broke
     */
    void verify(Object value);

    /**
     * Declares what this engine's rules mean, for the published document.
     *
     * <p>Called once, when the engine is installed. Each engine speaks its own vocabulary — the
     * jakarta constraints, its own annotations, both — and says so here; nothing in this module
     * knows any of them. An engine that declares nothing still enforces; it just says nothing.
     */
    default void describe(Constraints constraints) {}

    /**
     * How an engine's extension registers it. Two engines in one application is a boot failure
     * naming the second: which one validated a request is not something to leave to install order.
     */
    static void install(FlashContext ctx, Validation validation) {
        try {
            ctx.provide(Validation.class, validation);
        } catch (IllegalStateException alreadyThere) {
            throw new IllegalStateException("A validation engine is already installed and "
                    + validation.getClass().getSimpleName() + " would be the second: install one.", alreadyThere);
        }
        ValidationDocs.register(ctx, validation);
    }
}
