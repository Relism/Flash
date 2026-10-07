package dev.relism.flash.ext.grpc;

import java.io.IOException;

/** The server message stream of a streaming gRPC call. */
public final class GrpcOutput<T> {
    @FunctionalInterface
    interface Sender<T> { void send(T value) throws IOException; }

    private final Sender<T> sender;

    GrpcOutput(Sender<T> sender) {
        this.sender = sender;
    }

    public void send(T value) throws IOException {
        sender.send(value);
    }
}
