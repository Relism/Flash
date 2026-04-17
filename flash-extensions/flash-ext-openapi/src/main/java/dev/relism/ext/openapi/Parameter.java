package dev.relism.ext.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one OpenAPI parameter for a class-based handler operation.
 */
@Repeatable(Parameters.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Parameter {
    String name();
    ParameterIn in() default ParameterIn.QUERY;
    String description() default "";
    boolean required() default false;
    String example() default "";
    String[] examples() default {};
    SchemaType type() default SchemaType.STRING;
    String style() default "";
    boolean explode() default false;
    boolean allowEmptyValue() default false;
}
