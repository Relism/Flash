package dev.relism.flash.ext.data.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A field a client may sort the pages of its type by, read once at boot by {@link Pager}. Only these
 * fields and the id are accepted in {@code sort}: put it on columns that have an index.
 *
 * <pre>{@code
 * record User(long id, @Sortable String name, @Sortable Instant lastLogin) {}
 * }</pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Sortable {}
