package dev.relism.models;

import dev.relism.fpr.core.ByteView;
import dev.relism.http.HttpMethod;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.Value;
import lombok.experimental.NonFinal;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Immutable view of an incoming HTTP/1.1 request. Constructed by {@link dev.relism.RequestParser}
 * and passed directly to route handlers; never modified after creation (path/query params are
 * injected once by the router before the handler runs).
 *
 * <pre>{@code
 * server.get("/users/{id}", (req, res) -> {
 *     String id      = req.param("id");            // /users/42 → "42"
 *     String page    = req.query("page");           // ?page=2   → "2"
 *     String accept  = req.header("Accept");        // first Accept value
 *     byte[] body    = req.body().bytes();          // materialise body
 *     InputStream in = req.body().stream();         // zero-copy stream
 * });
 * }</pre>
 */
@Value
@ToString
public class Request {

    @Getter(lombok.AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    RequestBody body;

    /** Internal: the parsed request line (method, path, query, protocol, headers). */
    RequestLine requestLine;

    @NonFinal PathParams  pathParams;
    @NonFinal QueryParams queryParams;
    @NonFinal String cachedPath;

    /**
     * Remote socket address of the connected client. Set once at connection time from
     * {@link java.net.Socket#getRemoteSocketAddress()} : the {@link InetSocketAddress}
     * object already exists in the JDK and is passed by reference: zero allocation,
     * zero copy. {@code null} only in test-constructed requests.
     *
     * <p>Use {@link #remoteAddress()} to access it. String conversion
     * ({@code .getAddress().getHostAddress()}) is deferred to the caller, lazy and
     * only paid when actually needed.
     */
    @Getter(lombok.AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    InetSocketAddress remoteAddress;

    private Request(RequestLine requestLine, RequestBody body, InetSocketAddress remoteAddress) {
        this.requestLine   = requestLine;
        this.body          = body;
        this.pathParams    = null;
        this.queryParams   = null;
        this.remoteAddress = remoteAddress;
    }

    /**
     * Internal: injects path parameters after routing. Package-private so only
     * {@link PathParams#inject} (same package) can call it — not user code.
     * Use {@link PathParams#inject(Request, PathParams)} from routing code.
     */
    void setPathParams(PathParams p) { this.pathParams = p; }

    /** Test / manual constructor — {@code remoteAddress()} returns {@code null}. */
    public Request(RequestLine requestLine, byte[] body) {
        this(requestLine, RequestBody.of(body), null);
    }

    public static Request forParsed(RequestLine requestLine, InputStream stream,
                                    long contentLength, byte[] headerBuf,
                                    int bodyStart, int preBufLen,
                                    InetSocketAddress remoteAddress) {
        RequestBody rb = contentLength > 0  ? new RequestBody(stream, contentLength, headerBuf, bodyStart, preBufLen)
                       : contentLength == 0 ? RequestBody.empty()
                       : /* chunked */        new RequestBody(stream, -1L, null, 0, 0);
        return new Request(requestLine, rb, remoteAddress);
    }

    // ── Request line ──────────────────────────────────────────────────────────

    /** HTTP method ({@code GET}, {@code POST}, …). */
    public HttpMethod method() { return requestLine.getMethod(); }

    /**
     * Request path decoded as UTF-8. Includes a leading slash; never includes the query string.
     * Example: a request for {@code /users/42?page=1} returns {@code "/users/42"}.
     */
    public String path() {
        if (cachedPath != null) return cachedPath;
        ByteView v = requestLine.getPath();
        byte[] buf = new byte[v.length()];
        for (int i = 0; i < v.length(); i++) buf[i] = v.byteAt(i);
        return cachedPath = new String(buf, StandardCharsets.UTF_8);
    }

    // ── Headers ───────────────────────────────────────────────────────────────

    /**
     * Returns the first value of header {@code name}, or {@code null} if absent.
     * Lookup is case-insensitive ({@code "content-type"} and {@code "Content-Type"} are equivalent).
     */
    public String header(String name) { return requestLine.getHeaders().first(name); }

    /**
     * Returns all values of header {@code name} in declaration order.
     * Useful for headers that appear multiple times (e.g. {@code Accept}, {@code Cookie}).
     * Lookup is case-insensitive. Returns an empty list if the header is absent.
     */
    public List<String> headers(String name) { return requestLine.getHeaders().all(name); }

    /**
     * Returns all header values in declaration order, one entry per header line.
     * Useful for debugging; for targeted access prefer {@link #header(String)}.
     */
    public List<String> headers() { return requestLine.getHeaders().all(); }

    // ── Path parameters ───────────────────────────────────────────────────────

    /**
     * Returns the path parameter named {@code name}, or {@code null}.
     * Parameters are declared in the route pattern (e.g. {@code /users/{id}}) and
     * injected by the router before the handler runs. Returns {@code null} if this
     * route has no such parameter or the route is not parametric.
     */
    public String param(String name) { return pathParams != null ? pathParams.get(name) : null; }

    // ── Query parameters ──────────────────────────────────────────────────────

    /**
     * Returns the first query parameter named {@code name}, or {@code null}.
     * The query string is parsed lazily on the first call and cached for the request lifetime.
     * For {@code ?a=1&a=2}, returns {@code "1"}.
     */
    public String query(String name) { return resolveQueryParams().get(name); }

    /**
     * Returns all query parameters named {@code name} in declaration order.
     * For {@code ?tag=a&tag=b}, returns {@code ["a", "b"]}.
     * Returns an empty list if the parameter is absent.
     */
    public List<String> queries(String name) { return resolveQueryParams().getAll(name); }

    // ── Remote address ────────────────────────────────────────────────────────

    /**
     * Returns the remote socket address of the connected client, or {@code null}
     * for test-constructed requests.
     *
     * <p>The {@link InetSocketAddress} is the JDK object created during
     * {@link java.net.ServerSocket#accept()} — no allocation occurs here.
     * To obtain the IP string (lazy, allocates once):
     * <pre>{@code
     * InetSocketAddress addr = req.remoteAddress();
     * if (addr != null) String ip = addr.getAddress().getHostAddress();
     * }</pre>
     */
    public InetSocketAddress remoteAddress() { return remoteAddress; }

    // ── Body ──────────────────────────────────────────────────────────────────

    /**
     * Returns the request body accessor. Use {@link RequestBody#bytes()} to materialise
     * the full body or {@link RequestBody#stream()} for zero-copy streaming access.
     * The two modes are mutually exclusive per request.
     */
    public RequestBody body() { return body; }

    /** Discards unread body bytes; called by the server after each request on keep-alive connections. */
    public void drain() { body.drain(); }

    // ── Internal ─────────────────────────────────────────────────────────────

    /** Internal: case-insensitive header value comparison used by the server keep-alive logic. */
    public boolean headerEquals(String name, String value) {
        return requestLine.getHeaders().valueEqualsIgnoreCase(name, value);
    }

    private QueryParams resolveQueryParams() {
        if (queryParams == null) {
            ByteView raw = requestLine.getQuery();
            queryParams = raw != null ? new QueryParams(raw) : QueryParams.EMPTY;
        }
        return queryParams;
    }
}
