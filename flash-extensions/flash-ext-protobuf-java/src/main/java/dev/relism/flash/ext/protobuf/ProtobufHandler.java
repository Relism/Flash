package dev.relism.flash.ext.protobuf;

import dev.relism.flash.ext.serde.CodecHandler;

/** A handler that reads and answers binary Protocol Buffers. */
public abstract class ProtobufHandler<I, O> extends CodecHandler<Protobuf, I, Protobuf, O> {}
