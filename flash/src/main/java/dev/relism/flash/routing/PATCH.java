package dev.relism.flash.routing;

import dev.relism.flash.http.HttpMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Shorthand for {@code @Route(method = HttpMethod.PATCH, path = "…")}. */
@Route(method = HttpMethod.PATCH, path = "")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PATCH {
    String value();
}
