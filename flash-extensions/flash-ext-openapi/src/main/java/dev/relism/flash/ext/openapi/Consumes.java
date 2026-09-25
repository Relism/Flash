package dev.relism.flash.ext.openapi;

import dev.relism.flash.http.ContentType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The media type a handler reads its request body as, for the document alone.
 *
 * <p>A handler whose codecs are in its signature says this already, and is documented from them.
 * This is for one that reads its body by hand and wants the document to say so.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Consumes {
    ContentType value();
}
