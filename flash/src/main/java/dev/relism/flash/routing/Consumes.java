package dev.relism.flash.routing;

import dev.relism.flash.http.ContentType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The media type a handler reads its request body as.
 *
 * <p>Declared once on a handler base class — a JSON one, an XML one — and inherited by every
 * handler written against it, so nothing has to repeat it. Tooling reads it; the router does not.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Consumes {
    ContentType value();
}
