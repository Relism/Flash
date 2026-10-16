package dev.relism.flash.scanany;

import dev.relism.flash.routing.AnyMethod;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An extension's own routing annotation for every method, as WebDAV's {@code @Dav} is. */
@AnyMethod
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mounted {
    String value();
}
