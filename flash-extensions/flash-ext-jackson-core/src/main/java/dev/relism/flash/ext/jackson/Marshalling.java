package dev.relism.flash.ext.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.Middleware;

/**
 * Turns whatever a handler returns into a serialized body.
 *
 * <p>Pass-through for what is already a response: {@code null}, a {@link Response}, a
 * {@code byte[]} or a {@link CharSequence}. Everything else is serialized straight to bytes.
 */
public final class Marshalling {

    private Marshalling() {}

    public static Middleware of(ObjectMapper mapper, ContentType contentType) {
        return next -> (req, res) -> {
            Object out = next.handle(req, res);
            if (out == null || out instanceof Response || out instanceof byte[] || out instanceof CharSequence) return out;

            res.type(contentType);
            try {
                return mapper.writeValueAsBytes(out);
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("Could not serialize " + out.getClass().getName(), failure);
            }
        };
    }
}
