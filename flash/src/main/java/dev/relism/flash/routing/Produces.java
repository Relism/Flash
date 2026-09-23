package dev.relism.flash.routing;

import dev.relism.flash.http.ContentType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The media type a handler answers in.
 *
 * <p>Independent of {@link Consumes}: what a request carries says nothing about what the answer
 * is written as, and a handler that takes JSON may well answer something else. Declare it on the
 * handler, or once on a base class every handler of that kind extends.
 *
 * <p>Descriptive, like {@link Consumes}: tooling reads it, the router does not. What actually
 * serializes a response is the middleware the route runs under.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Produces {
    ContentType value();
}
