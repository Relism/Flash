package dev.relism.flash.ext.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** The caller's credential must grant every one of these scopes. Implies {@link Authenticated}. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@java.lang.annotation.Target(ElementType.TYPE)
public @interface ScopesAllowed {

    String[] value();
}
