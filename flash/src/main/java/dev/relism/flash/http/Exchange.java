package dev.relism.flash.http;

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
        if (duplex != null) duplex.execute(this);
        else streaming.write(transport);
    }

    public Duplex transport() {
        return transport;
    }
}
