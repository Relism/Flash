package dev.relism.flash.ext.openapi;

import dev.relism.flash.http.ContentType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * OpenAPI response content descriptor.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface Content {
    ContentType contentType() default ContentType.JSON;
    Class<?> schema() default Void.class;
    boolean array() default false;

    /** One example body, shown beside the schema. */
    String example() default "";
}
