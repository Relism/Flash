package dev.relism.flash.extension;

/**
 * Semantic execution phases for {@link FlashExtension#priority()}.
 *
 * <p>Phase determines the order in which annotation-processor middlewares are injected into
 * the chain. Lower value = runs earlier (outermost wrapper = first at request time).
 *
 * <pre>
 * Request ──► EARLY middlewares ──► DEFAULT middlewares ──► LATE middlewares ──► handler
 * </pre>
 *
 * <p>Within the same phase, extensions execute in install order (sort is stable).
 * Raw integers are valid for fine-grained ordering within a phase
 * (e.g. {@code ExtensionPhase.EARLY.value + 10}).
 */
public enum ExtensionPhase {

    /** Security guards, rate limiting — must short-circuit before expensive processing. */
    EARLY(100),

    /** Normal application extensions. Default when {@link FlashExtension#priority()} is not overridden. */
    DEFAULT(500),

    /** Observability, logging, diagnostics — must observe after all business logic. */
    LATE(900);

    /** The integer priority value used for sorting. */
    public final int value;

    ExtensionPhase(int value) { this.value = value; }
}
