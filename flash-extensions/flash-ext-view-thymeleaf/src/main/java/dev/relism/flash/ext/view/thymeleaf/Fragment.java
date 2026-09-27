package dev.relism.flash.ext.view.thymeleaf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** One fragment of a Thymeleaf template, rendered alone: the module's default fragment when {@link #value()} is empty. */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Fragment {
    String template();
    String value() default "";
}
