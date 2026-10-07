package dev.relism.flash.ext.serde;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http.ContentType;

/** An encoder already bound to one concrete type. */
public interface Encoder {
    void encode(Object value, ByteWriter output) throws Exception;
    ContentType type();
}
