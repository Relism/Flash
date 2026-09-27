package dev.relism.flash.ext.view.jte;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The jte template a {@link dev.relism.flash.ext.view.core.ViewHandler} renders, relative to the template root. */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Template {
    String value();
}
