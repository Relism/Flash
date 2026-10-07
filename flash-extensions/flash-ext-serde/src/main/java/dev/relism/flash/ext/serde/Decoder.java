package dev.relism.flash.ext.serde;

import dev.relism.flash.http.Request;

/** A decoder already bound to one concrete type. */
@FunctionalInterface
public interface Decoder {
    Object decode(byte[] bytes, int offset, int length) throws Exception;

    default Object decode(Request request) throws Exception {
        byte[] bytes = request.body().bytes();
        return decode(bytes, 0, bytes.length);
    }
}
