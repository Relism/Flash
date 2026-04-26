package dev.relism.flash.ext.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Optional array-specific schema metadata. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface ArraySchema {
    Class<?> itemClass() default Void.class;
    boolean uniqueItems() default false;
    int minItems() default -1;
    int maxItems() default -1;
}
