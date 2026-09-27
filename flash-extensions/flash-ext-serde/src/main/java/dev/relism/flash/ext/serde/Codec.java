package dev.relism.flash.ext.serde;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;

import java.lang.reflect.Type;

/**
 * One format, both directions.
 *
 * <p>An implementation is a service an application provides once — {@code Json}, {@code Xml}, a
 * protobuf one — and a {@link CodecHandler} names it in its signature to be read or written with.
 * What it does inside and which library it wraps are its own business.
 *
 * <p>Checking what comes in is not: an implementation verifies what it parsed against the installed
 * {@code Validation} engine, when there is one. It belongs here and not in {@link CodecHandler},
 * because a codec is also read from directly.
 */
public interface Codec {

    /**
     * Reads the request body as {@code type}, which may be generic, and verifies what it read: a
     * body that cannot be parsed is a 400, one that breaks a rule a 422.
     *
     * <p>Skipping the check hands every handler reading through this codec unverified input, and
     * nothing further along catches it.
     */
    <T> T read(Request request, Type type) throws Exception;

    /** The same, for a plain class, where the compiler can check what comes back. */
    default <T> T read(Request request, Class<T> type) throws Exception {
        return read(request, (Type) type);
    }

    /** Writes {@code value} and sets this codec's content type on the response. */
    Object write(Response response, Object value) throws Exception;

    /**
     * Called once at boot with each type a handler reads or writes through this codec. A codec
     * that must know its types ahead — one whose bindings are generated at build time — resolves
     * it here, so a type it cannot handle fails the boot instead of the first request.
     */
    default void prepare(Type type) {}

    /** What this codec writes, which is also what the published document says it writes. */
    ContentType contentType();
}
