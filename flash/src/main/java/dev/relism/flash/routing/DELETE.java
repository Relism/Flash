package dev.relism.flash.routing;

import dev.relism.flash.http.HttpMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A handler class that answers {@code DELETE} at the given path. */
@Verb(HttpMethod.DELETE)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DELETE {
    String value();
}
