package dev.relism.flash.ext.validation;

import dev.relism.flash.Module;
import dev.relism.flash.Services;
import dev.relism.flash.ext.openapi.OpenApiContributor;

import java.util.Optional;

/**
 * Verifies that a value satisfies the rules its own type declares.
 *
 * <p>One method, because that is the whole of what an engine does. An implementation wraps a
 * validation library — {@code flash-ext-validation-jakarta}, {@code flash-ext-validation-avaje} —
 * and an application installs exactly one of them: it is added as {@code Validation}, so a second
 * one fails the boot naming both. What a rule means, which annotations exist and
 * how they are enforced is the engine's business; this module knows only when to ask.
 *
 * <p>A format's codec calls it on a body it has just read, so a request that breaks a rule is a 422
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
     * <p>Called once per app, when the document is first built. Each engine speaks its own vocabulary — the
     * jakarta constraints, its own annotations, both — and says so here; nothing in this module
     * knows any of them. An engine that declares nothing still enforces; it just says nothing.
     */
    default void describe(Constraints constraints) {}

    /**
     * The engine of a codec family, empty when none is installed. A family is a sub-interface of
     * {@code Validation} that its codec's module defines, for values the generic engines cannot
     * check; the codec asks for it here when it binds.
     *
     * @throws IllegalStateException when an engine of the family was added as plain
     *         {@code Validation}, where its codec would never ask it, or when two were added
     */
    static <F extends Validation> Optional<F> family(Services services, Class<F> family) {
        for (Validation engine : services.all(Validation.class)) {
            if (family.isInstance(engine)) throw new IllegalStateException(engine.getClass().getName() + " is a "
                    + family.getSimpleName() + ": add it as " + family.getSimpleName() + ", not as Validation");
        }
        return services.find(family);
    }

    /**
     * What an engine's module installs beside it: the engine's rules in the published document,
     * when flash-ext-openapi is on the classpath.
     */
    static Module docs() {
        return Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                () -> app -> app.add(OpenApiContributor.class, ValidationDocs.class));
    }
}
