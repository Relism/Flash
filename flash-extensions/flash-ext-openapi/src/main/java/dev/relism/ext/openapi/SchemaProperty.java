package dev.relism.ext.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Optional alias for property-level schema metadata. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface SchemaProperty {
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
