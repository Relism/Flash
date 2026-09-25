package dev.relism.flash.ext.validation;

/**
 * What one rule adds to a property's schema.
 *
 * <p>Handed to the function an engine registered with {@link Constraints}. A fact never overwrites
 * what an explicit {@code @Schema} annotation already said: the code describes what the annotation
 * left unsaid.
 */
public interface Facts {

    /** The property's declared type, for a rule that means one thing on text and another on a list. */
    Class<?> type();

    /** Adds a schema keyword — {@code maxLength}, {@code minimum}, {@code format}. */
    void put(String keyword, Object value);

    /**
     * Says in words what a keyword cannot.
     *
     * <p>For a rule whose shape a reader cannot infer — a regular expression above all — the
     * sentence lands in the property's description, after whatever it already said.
     */
    void describe(String sentence);

    /** Marks the property required on its schema's object. */
    void required();
}
