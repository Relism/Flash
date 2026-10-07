package dev.relism.flash.routing;

import dev.relism.flash.http.HttpMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@link dev.relism.flash.sse.SseHandler} at the given path.
 */
@Verb(HttpMethod.GET)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Sse {
    String value();
}
