package dev.relism.flash.routing;

import java.util.Objects;

/** Stable boot-time identity of a middleware node. Never consulted while handling a request. */
public record MiddlewareKey(String value) {
    public MiddlewareKey {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Middleware key cannot be blank");
    }
    public static MiddlewareKey of(String value) { return new MiddlewareKey(value); }
}
