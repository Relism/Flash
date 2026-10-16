package dev.relism.flash.routing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a routing annotation whose route answers every method, standard or not, as
 * {@link dev.relism.flash.App#any} does. Every such annotation has a {@code String value()} path.
 *
 * <pre>{@code
 * @AnyMethod
 * @Retention(RetentionPolicy.RUNTIME)
 * @Target(ElementType.TYPE)
 * public @interface Proxy { String value(); }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface AnyMethod {}
