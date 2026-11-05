package dev.relism.flash.ext.validation;

import dev.relism.flash.Module;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.serde.Check;

/**
 * Verifies that a value satisfies the rules its own type declares.
 *
 * <p>An implementation wraps a validation library ({@code flash-ext-validation-jakarta},
 * {@code flash-ext-validation-avaje}, {@code flash-ext-validation-protovalidate}) and installs itself
 * through {@link #module}. Several engines live in one application: each type a codec decodes is
 * checked by the one engine that {@link #checks} it, whatever the codec, so a request that breaks a
 * rule is a 422 and never reaches a handler. What a rule means, which annotations exist and how they
 * are enforced is the engine's business; this module knows only when to ask.
 */
public interface Validation {

    /**
     * Verifies {@code value} against its type's rules.
     *
     * @throws ValidationException 422, listing every rule the value broke
     */
    void verify(Object value);

    /**
     * Whether this engine has rules for values of {@code type}, asked once per type at boot: a type
     * no engine checks is decoded and handed over with no call at all. A type whose values may be of
     * a subclass with rules (an interface, an abstract class) is one the engine checks. Every type
     * by default.
     */
    default boolean checks(Class<?> type) {
        return true;
    }

    /**
     * Declares what this engine's rules mean, for the published document.
     *
     * <p>Called once per app, when the document is first built. Each engine speaks its own vocabulary — the
     * jakarta constraints, its own annotations, both — and says so here; nothing in this module
     * knows any of them. An engine that declares nothing still enforces; it just says nothing.
     */
    default void describe(Constraints constraints) {}

    /**
     * What an engine's module installs: the engine as a {@code Validation}, its checks on everything a
     * codec decodes when flash-ext-serde is on the classpath, and its rules in the published document
     * when flash-ext-openapi is.
     *
     * <pre>{@code
     * @Override public void install(App app) {
     *     app.install(Validation.module(this));
     * }
     * }</pre>
     */
    static Module module(Validation engine) {
        return app -> app.add(Validation.class, engine).install(
                Module.ifPresent("dev.relism.flash.ext.serde.Check",
                        () -> checks -> checks.add(Check.class, ValidationCheck.class)),
                Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                        () -> docs -> docs.add(OpenApiContributor.class, ValidationDocs.class)));
    }
}
