package dev.relism.flash.ext.validation;

import java.lang.annotation.Annotation;
import java.util.function.BiConsumer;

/**
 * Where an engine says what its annotations mean, so the published document can say it too.
 *
 * <pre>{@code
 * @Override public void describe(Constraints constraints) {
 *     constraints.of(Pattern.class, (pattern, facts) -> facts.put("pattern", pattern.regexp()));
 *     constraints.of(NotNull.class, (notNull, facts) -> facts.required());
 * }
 * }</pre>
 *
 * <p>Nothing here knows a constraint by name: an engine registers the annotations it enforces, and
 * two engines that share an annotation are free to describe it differently, because each documents
 * what it actually does with it.
 */
public interface Constraints {

    /**
     * Registers what {@code annotation} adds to the schema of the property carrying it.
     *
     * <p>Called once per annotation at boot; the function itself runs when the document is built,
     * once per revision, never on a request.
     */
    <A extends Annotation> void of(Class<A> annotation, BiConsumer<A, Facts> facts);
}
