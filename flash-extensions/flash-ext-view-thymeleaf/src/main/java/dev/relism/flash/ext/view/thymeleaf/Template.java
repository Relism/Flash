package dev.relism.flash.ext.view.thymeleaf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The Thymeleaf template a {@link dev.relism.flash.ext.view.core.ViewHandler} renders, without prefix or suffix. */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Template {
    String value();
}
