package dev.relism.flash.http;

/**
 * A route whose protocol owns an exchange beyond a single response value.
 *
 * <p>{@link #prepare(Request, Response)} validates the handshake and prepares its response. Only
 * after the complete middleware chain has returned does Flash bind the transport and call
 * {@link #handle(Request, Response, Duplex)}. Extensions define framing and directionality in a
 * concrete subclass; this base imposes neither messages nor cardinality.
 */
public abstract non-sealed class DuplexHandler extends Handler {

    /** Validates the request and sets status and headers, without sending them. */
    protected void prepare(Request request, Response response) throws Exception {}

    /** Whether HTTP/1 hands the connection to this protocol after the handshake. */
    protected boolean switchesProtocol(Request request) {
        return false;
    }

    /** Runs the protocol once every middleware has returned. */
    protected abstract void handle(Request request, Response response, Duplex duplex) throws Exception;

    @Override
    protected final void prepareExchange(Exchange exchange) throws Exception {
        prepare(exchange.request(), exchange.response());
        exchange.duplex(this, switchesProtocol(exchange.request()));
    }

    final void execute(Exchange exchange) throws Exception {
        handle(exchange.request(), exchange.response(), exchange.transport());
    }
}