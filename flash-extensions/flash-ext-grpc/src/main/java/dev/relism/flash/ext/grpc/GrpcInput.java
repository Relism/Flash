package dev.relism.flash.ext.grpc;

import java.io.IOException;

/** The client message stream of a client-streaming or bidirectional call. */
public abstract class GrpcInput<T> {
    /** The next message, or {@code null} after the client half-closes. */
    public abstract T next() throws IOException;
}
