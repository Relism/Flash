package dev.relism.flash.ext.grpc;

import dev.relism.flash.http.Request;

/** Any number of client messages, one server message. */
public abstract non-sealed class GrpcClientStreaming<I, O> extends GrpcHandler<I, O> {
    protected abstract O handle(Request request, GrpcInput<I> input) throws Exception;

    @Override
    final void invoke(Request request, Call<I, O> call) throws Exception {
        call.send(handle(request, call));
    }
}
