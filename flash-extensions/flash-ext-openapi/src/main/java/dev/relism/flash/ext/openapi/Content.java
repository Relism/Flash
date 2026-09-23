package dev.relism.flash.ext.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a response carries: the schema, and an example of it.
 *
 * <p>The media type is not here — it is the handler's, declared with
 * {@link dev.relism.flash.routing.Produces @Produces} and JSON when nothing says otherwise. A
 * response with no schema, and no return type to infer one from, is documented without a body.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface Content {
    Class<?> schema() default Void.class;
    boolean array() default false;

    /** One example body, shown beside the schema. */
    String example() default "";
}
