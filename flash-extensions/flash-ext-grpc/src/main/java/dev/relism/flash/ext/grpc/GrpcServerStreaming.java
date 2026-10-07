package dev.relism.flash.ext.grpc;

import dev.relism.flash.http.Request;

/** One client message, any number of server messages. */
public abstract non-sealed class GrpcServerStreaming<I, O> extends GrpcHandler<I, O> {
    protected abstract void handle(Request request, I input, GrpcOutput<O> output) throws Exception;

    @Override
    final void invoke(Request request, Call<I, O> call) throws Exception {
        I input = call.required();
        call.requireEnd();
        handle(request, input, new GrpcOutput<>(call::send));
    }
}
