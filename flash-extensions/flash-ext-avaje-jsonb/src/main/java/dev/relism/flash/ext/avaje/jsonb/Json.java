package dev.relism.flash.ext.avaje.jsonb;

import dev.relism.flash.Bound;
import dev.relism.flash.Services;
import dev.relism.flash.ext.serde.Codec;
import dev.relism.flash.ext.validation.Validation;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import io.avaje.json.JsonException;
import io.avaje.jsonb.Jsonb;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;

/**
 * JSON in and out through Avaje Jsonb, checked by the installed validation engine.
 *
 * <p>Every type is bound by an adapter generated at build time, so nothing is reflected on and a
 * body is parsed straight off the request stream into Jsonb's recycled buffers. A handler takes it
 * as a constructor parameter and calls it on the hot path: {@link Jsonb} is thread-safe.
 *
 * <p>A handler whose whole body is one type has nothing to write at all: see {@link JsonHandler}.
 */
public final class Json implements Codec, Bound {

    private final Jsonb jsonb;

    /** The engine that checks what is read, or null when none is installed; written once at boot. */
    private Validation validation;

    public Json(Jsonb jsonb) {
        this.jsonb = jsonb;
    }

    /**
     * Reads the request body as {@code type} and verifies its rules. A malformed or truncated body
     * is a 400, a body that breaks a rule is a 422, and neither reaches the handler.
     */
    @Override
    public <T> T read(Request request, Type type) throws Exception {
        T value;
        try {
            value = jsonb.<T>type(type).fromJson(request.body().stream());
        } catch (JsonException malformed) {
            throw HttpException.badRequest("Invalid request body: " + malformed.getMessage());
        }
        if (validation != null) validation.verify(value);
        return value;
    }

    /** Serializes {@code value} by its runtime type into one array, the form the response sends. */
    @Override
    public byte[] write(Response response, Object value) {
        response.type(ContentType.JSON);
        return jsonb.toJsonBytes(value);
    }

    /**
     * Resolves the adapter for {@code type} at boot, so a type the generator never saw fails there.
     * An interface or an abstract class is left to its runtime type, which is what is written.
     */
    @Override
    public void prepare(Type type) {
        if (type instanceof Class<?> c && (c.isInterface() || Modifier.isAbstract(c.getModifiers()))) return;
        jsonb.type(type);
    }

    @Override
    public ContentType contentType() {
        return ContentType.JSON;
    }

    /** Jsonb itself, for everything these methods do not cover. */
    public Jsonb jsonb() {
        return jsonb;
    }

    /** Takes the app's validation engine at start, and says so once when there is none. */
    @Override
    public void bind(Services services) {
        validation = services.find(Validation.class).orElse(null);
        if (validation == null) LoggerFactory.getLogger(Json.class)
                .info("No validation engine installed: {} bodies are parsed and not checked", ContentType.JSON);
    }
}
