package dev.relism.flash.ext.serde;

import dev.relism.flash.http.Request;

import java.lang.reflect.Type;

/**
 * Reads a {@link CodecRequestHandler}'s input from the request itself instead of its body: a module
 * registers one as a service, and a handler whose input type is {@link FromRequest} asks the binders
 * for it once, at boot.
 *
 * <pre>{@code
 * app.add(InputBinder.class, MyBinder.class);
 * }</pre>
 */
public interface InputBinder {

    /**
     * A reader for {@code type}, prepared now, or {@code null} to leave the type to the next binder.
     * {@code codec} is the handler's input codec, for a binder that names fields as the format does.
     */
    Reader reader(Type type, Codec codec);

    /** Produces a handler's input on each request. */
    @FunctionalInterface
    interface Reader {
        Object read(Request request) throws Exception;
    }
}
