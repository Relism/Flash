package dev.relism.flash.ext.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Minimal OpenAPI schema metadata for model classes and properties. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface Schema {
    String name() default "";
    String title() default "";
    String description() default "";
    String format() default "";
    String example() default "";
    String[] enumeration() default {};
    boolean nullable() default false;
    boolean required() default false;
    boolean deprecated() default false;
    boolean hidden() default false;
}
