package dev.relism.ext.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one OpenAPI response for a class-based handler operation.
 */
@Repeatable(APIResponses.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface APIResponse {
    String responseCode();
    String description() default "";
    Content content() default @Content;
}
