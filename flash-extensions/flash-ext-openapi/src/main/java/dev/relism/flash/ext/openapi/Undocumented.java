package dev.relism.flash.ext.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Keeps a route out of the published document.
 *
 * <p>Every class-based route is documented, which is what stops a document from lying by
 * omission. Some routes are not part of the API anyway — a health check, an internal callback,
 * something on its way out — and this says so, once, where the handler is.
 *
 * <p>Inherited: on a base class it leaves out every handler written against it.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Undocumented {}
