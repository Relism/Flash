package dev.relism.flash.http;

/**
 * A classic HTTP handler: one request produces one response value.
 *
 * <p>Returning a {@link Response} replaces the current response, returning {@code null} keeps it,
 * and every other value becomes its body. Use {@link #of(RequestAction)} for lambdas.
 */
public abstract non-sealed class RequestHandler extends Handler {

    protected abstract Object handle(Request request, Response response) throws Exception;

    @Override
    protected final void prepareExchange(Exchange exchange) throws Exception {
        exchange.answer(handle(exchange.request(), exchange.response()));
    }

    /** Adapts the public lambda shape without introducing a second handler hierarchy. */
    public static RequestHandler of(RequestAction action) {
        return new RequestHandler() {
            @Override
            protected Object handle(Request request, Response response) throws Exception {
                return action.handle(request, response);
            }
        };
    }
}
