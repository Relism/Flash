package dev.relism.flash.ext.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.Middleware;

/**
 * Outbound JSON marshalling middleware for class-based and lambda routes.
 *
 * <p>{@link #autoJson()} marshals any non-body-native return value to JSON bytes,
 * writes {@code Content-Type: application/json}, and returns {@code byte[]} so
 * the Flash write path stays direct.
 *
 * <p>Pass-through return types:
 * <ul>
 *   <li>{@code null}</li>
 *   <li>{@link Response}</li>
 *   <li>{@code byte[]}</li>
 *   <li>{@link String}</li>
 *   <li>{@link CharSequence}</li>
 * </ul>
 */
public final class JacksonMiddleware {

    private final ObjectMapper mapper;

    JacksonMiddleware(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Automatic JSON marshalling policy.
     *
     * <p>For non-pass-through return values, serializes with Jackson directly to
     * {@code byte[]} and sets response content type to JSON.
     *
     * @throws IllegalStateException when serialization fails
     */
    public Middleware autoJson() {
        return next -> (req, res) -> {
            Object out = next.handle(req, res);
            if (isPassThrough(out)) return out;

            res.type(ContentType.JSON);
            try {
                return mapper.writeValueAsBytes(out);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "Failed to serialize handler result as JSON: " + out.getClass().getName(), e);
            }
        };
    }

    private static boolean isPassThrough(Object out) {
        return out == null
                || out instanceof Response
                || out instanceof byte[]
                || out instanceof CharSequence;
    }
}
