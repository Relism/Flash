package dev.relism.flash.ext.grpc;

import dev.relism.flash.http.Request;

/** Any number of messages in both directions. */
public abstract non-sealed class GrpcBidiStreaming<I, O> extends GrpcHandler<I, O> {
    protected abstract void handle(Request request, GrpcInput<I> input, GrpcOutput<O> output) throws Exception;

    @Override
    final void invoke(Request request, Call<I, O> call) throws Exception {
        handle(request, call, new GrpcOutput<>(call::send));
    }
}
