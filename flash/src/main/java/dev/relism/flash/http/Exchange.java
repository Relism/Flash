package dev.relism.flash.http;

import java.util.concurrent.Callable;

/**
 * One request, its mutable response plan and the transport that executes it. Reused by a
 * connection.
 *
 * <p>Handlers and middleware only prepare this object. {@link #execute()} starts a delayed
 * streaming body or duplex protocol after preparation has completed.
 */
public final class Exchange {
    private Request request;
    private Response response;
    private Duplex transport;
    private DuplexHandler duplex;
    private boolean switchProtocol;
    private Around around;

    /** Encloses the work {@link #execute()} runs after the middleware chain has returned. */
    @FunctionalInterface
    public interface Around {
        void run(Callable<?> deferred) throws Exception;
    }

    public Exchange reset(Request request, Response response, Duplex transport) {
        this.request = request;
        this.response = response;
        this.transport = transport;
        clear();
        return this;
    }

    public Request request() {
        return request;
    }

    public Response response() {
        return response;
    }

    public void answer(Object value) {
        response = value instanceof Response answer ? answer : value == null ? response : response.setBody(value);
    }

    public void recover(Object value) {
        clear();
        answer(value);
    }

    private void clear() {
        duplex = null;
        switchProtocol = false;
        around = null;
    }

    /** Whether a duplex handler or a streaming body is left for {@link #execute()} to run. */
    public boolean deferred() {
        return duplex != null || response.streamingBody() != null;
    }

    /**
     * Has {@code scope} enclose the deferred work, so what a middleware set up around the chain (the
     * caller's identity, a transaction) also holds while a duplex handler or a streaming body runs.
     * A middleware registers it after {@code next} returns; the outermost one encloses the others.
     */
    public void around(Around scope) {
        Around inner = around;
        around = inner == null ? scope : deferred -> scope.run(() -> {
            inner.run(deferred);
            return null;
        });
    }

    public void duplex(DuplexHandler handler, boolean switchProtocol) {
        duplex = handler;
        this.switchProtocol = switchProtocol;
    }

    /** Executes only bodies that must run after middleware; fixed responses stay with the writer. */
    public void execute() throws Exception {
        Response.Streaming streaming = response.streamingBody();
        if (duplex == null && streaming == null) return;
        if (transport == null) throw new IllegalStateException("this exchange has no streaming transport");
        transport.bind(response, switchProtocol);
        try {
            if (around == null) run(streaming);
            else around.run(() -> {
                run(streaming);
                return null;
            });
        } catch (AnsweredFailure answered) {
            // the handler already told its peer
        }
    }

    private void run(Response.Streaming streaming) throws Exception {
        if (duplex != null) duplex.execute(this);
        else streaming.write(transport);
    }

    public Duplex transport() {
        return transport;
    }
}
