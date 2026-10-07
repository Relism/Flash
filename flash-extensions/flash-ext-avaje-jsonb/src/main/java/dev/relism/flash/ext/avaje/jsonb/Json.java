package dev.relism.flash.ext.avaje.jsonb;

import dev.relism.flash.Bound;
import dev.relism.flash.Services;
import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.ext.serde.Codec;
import dev.relism.flash.ext.serde.Decoder;
import dev.relism.flash.ext.serde.Encoder;
import dev.relism.flash.ext.validation.Validation;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Response;
import io.avaje.json.JsonException;
import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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

    @Override
    public Decoder decoder(Type type) {
        JsonType<Object> bound = bound(type);
        return new Decoder() {
            @Override public Object decode(byte[] bytes, int offset, int length) {
                return parse(type, bound, new ByteArrayInputStream(bytes, offset, length), "message");
            }
            @Override public Object decode(dev.relism.flash.http.Request request) {
                return parse(type, bound, request.body().stream(), "request body");
            }
        };
    }

    @Override
    public Encoder encoder(Type type) {
        JsonType<Object> bound = bound(type);
        return new Encoder() {
            @Override
            public void encode(Object value, ByteWriter out) {
                JsonType<Object> adapter = bound != null ? bound : jsonb.type((Type) value.getClass());
                adapter.toJson(value, out);
            }

            @Override
            public ContentType type() {
                return ContentType.JSON;
            }
        };
    }

    @Override
    public byte[] write(Response response, Object value) {
        response.type(ContentType.JSON);
        return jsonb.toJsonBytes(value);
    }

    @Override
    public ContentType contentType() {
        return ContentType.JSON;
    }

    public Jsonb jsonb() {
        return jsonb;
    }

    @Override
    public void bind(Services services) {
        validation = services.find(Validation.class).orElse(null);
        if (validation == null) LoggerFactory.getLogger(Json.class)
                .info("No validation engine installed: {} bodies are parsed and not checked", ContentType.JSON);
    }

    /** The adapter for {@code type}, resolved now; null for an abstract type, whose adapter goes by the value. */
    private JsonType<Object> bound(Type type) {
        if (type instanceof Class<?> c && (c.isInterface() || Modifier.isAbstract(c.getModifiers()))) return null;
        return jsonb.type(type);
    }

    private Object parse(Type type, JsonType<Object> bound, InputStream in, String what) {
        Object value;
        try {
            value = (bound != null ? bound : jsonb.<Object>type(type)).fromJson(in);
        } catch (JsonException malformed) {
            throw HttpException.badRequest("Invalid " + what + ": " + malformed.getMessage());
        }
        if (validation != null) validation.verify(value);
        return value;
    }
}
