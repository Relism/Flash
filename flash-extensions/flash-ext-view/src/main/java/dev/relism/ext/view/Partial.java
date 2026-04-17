package dev.relism.ext.view;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a class-based route handler to a partial template render.
 *
 * <p>Useful for progressive/fragment updates (e.g. HTMX). When {@link #slot()} is blank,
 * the adapter default slot is used.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Partial {
    /** Template containing the fragment slot. */
    String template();

    /** Optional fragment slot selector. */
    String slot() default "";
}
