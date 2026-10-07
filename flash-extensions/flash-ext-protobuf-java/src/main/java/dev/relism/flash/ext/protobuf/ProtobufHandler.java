package dev.relism.flash.ext.protobuf;

import dev.relism.flash.ext.serde.CodecRequestHandler;

/** A handler that reads and answers binary Protocol Buffers. */
public abstract class ProtobufHandler<I, O> extends CodecRequestHandler<Protobuf, I, Protobuf, O> {}
