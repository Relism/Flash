package dev.relism.ext.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Minimal OpenAPI schema metadata for model classes and properties. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface Schema {
    String name() default "";
    String description() default "";
    String format() default "";
    String example() default "";
    boolean nullable() default false;
    boolean required() default false;
    boolean hidden() default false;
}
