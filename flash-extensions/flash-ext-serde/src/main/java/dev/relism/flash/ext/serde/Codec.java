package dev.relism.flash.ext.serde;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;

import java.lang.reflect.Type;

/**
 * One format, both directions.
 *
 * <p>An implementation is a service an application provides once — {@code Json}, {@code Xml}, a
 * protobuf one — and a {@link CodecHandler} names it in its signature to be read or written with.
 * What it does inside, which library it wraps and what it checks on the way in are its own
 * business.
 */
public interface Codec {

    /** Reads the request body as {@code type}, which may be generic. */
    <T> T read(Request request, Type type) throws Exception;

    /** The same, for a plain class, where the compiler can check what comes back. */
    default <T> T read(Request request, Class<T> type) throws Exception {
        return read(request, (Type) type);
    }

    /** Writes {@code value} and sets this codec's content type on the response. */
    Object write(Response response, Object value) throws Exception;

    /** What this codec writes, which is also what the published document says it writes. */
    ContentType contentType();
}
