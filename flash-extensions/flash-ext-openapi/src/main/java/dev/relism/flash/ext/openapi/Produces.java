package dev.relism.flash.ext.openapi;

import dev.relism.flash.http.ContentType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The media type a handler answers in, for the document alone.
 *
 * <p>Independent of {@link Consumes}: what a request carries says nothing about what the answer
 * is written as. Like it, this is for a handler that writes its body by hand; one whose codecs
 * are in its signature is documented from them, and needs neither.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Produces {
    ContentType value();
}
