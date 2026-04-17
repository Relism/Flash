package dev.relism.routing;

import dev.relism.http.HttpMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Shorthand for {@code @Route(method = HttpMethod.PURGE, path = "…")}. */
@Route(method = HttpMethod.PURGE, path = "")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PURGE {
    String value();
}
