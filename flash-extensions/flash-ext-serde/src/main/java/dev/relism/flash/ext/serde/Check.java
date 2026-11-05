package dev.relism.flash.ext.serde;

import java.lang.reflect.Type;
import java.util.function.Consumer;

/**
 * Looks at every value a codec decodes before a handler gets it, and throws to refuse it: what a
 * validation engine's module adds as a service. Every protocol takes its decoders through
 * {@link Decoder#of}, so a body, an NDJSON line and a gRPC message are all checked.
 *
 * <pre>{@code
 * app.add(Check.class, MyCheck.class);
 * }</pre>
 */
@FunctionalInterface
public interface Check {

    /** What checks each value decoded as {@code type}, prepared now, or null when there is nothing to check. */
    Consumer<Object> of(Type type);
}
