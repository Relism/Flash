package dev.relism.flash.ext.grpc;

import dev.relism.flash.http.Request;

/** One client message, one server message. */
public abstract non-sealed class GrpcUnary<I, O> extends GrpcHandler<I, O> {
    protected abstract O handle(Request request, I input) throws Exception;

    @Override
    final void invoke(Request request, Call<I, O> call) throws Exception {
        I input = call.required();
        call.requireEnd();
        call.send(handle(request, input));
    }
}
