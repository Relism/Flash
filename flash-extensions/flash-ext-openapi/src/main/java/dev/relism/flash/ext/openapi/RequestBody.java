package dev.relism.flash.ext.openapi;

import dev.relism.flash.http.ContentType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The body this operation takes, for a handler that reads it by hand.
 *
 * <p>A handler extending {@code BodyHandler} — {@code JsonHandler} and its like — needs none of
 * this: its body type is its type argument and its media type comes from {@code @Consumes}. Use
 * this when the body is read straight off the request, or to describe it as something other than
 * what the handler parses.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RequestBody {

    Class<?> value();

    ContentType contentType() default ContentType.JSON;

    boolean array() default false;

    boolean required() default true;

    String description() default "";
}
