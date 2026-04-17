package dev.relism.ext.view;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a class-based route handler to a full-page template.
 *
 * <p>Use on subclasses of {@link dev.relism.models.RequestHandler}, typically
 * {@link ViewHandler}. The handler must also declare a route annotation
 * ({@code @Route}, {@code @GET}, {@code @POST}, ...).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Page {
    /** Template name/path (engine-specific). */
    String value();
}
