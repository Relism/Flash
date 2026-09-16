package dev.relism.flash.ext.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Anyone may call the handler. A caller who authenticates is still identified; one whose credential
 * is rejected is treated as anonymous rather than refused.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@java.lang.annotation.Target(ElementType.TYPE)
public @interface PermitAll {}
