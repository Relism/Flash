package dev.relism.flash.ext.serde;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A type a {@link CodecRequestHandler} takes from the request itself, through an {@link InputBinder},
 * never from its body. Only these types are offered to the binders, and a handler whose input is one
 * that no binder reads fails the boot instead of decoding its body.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FromRequest {}
