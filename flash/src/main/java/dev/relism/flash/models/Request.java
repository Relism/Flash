package dev.relism.flash.models;

import dev.relism.flash.Flash;
import dev.relism.flash.RequestParser;
import dev.relism.flash.bytes.ArrayBackedByteView;
import dev.relism.fpr.core.ByteView;
import dev.relism.flash.http.HttpMethod;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * View of an incoming HTTP/1.1 request. Constructed once per connection by {@link RequestParser}
 * and repositioned (never reallocated) for every request on that connection — never modified by
 * user code after creation (path/query params are injected once by the router before the
 * handler runs).
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
 *
 * A {@code Request} instance is owned by its connection (HTTP/1.1) or its stream (HTTP/2) and is
 * recycled after the handler returns. <b>Do not retain it</b> — the same instance is repositioned
 * over the next request's data as soon as this one's handler returns. {@code equals}/
 * {@code hashCode} are the inherited identity-based {@link Object} versions and are meaningless
 * across requests (compare two different {@code Request}s from the same connection and they may
 * be {@code ==} to each other despite describing entirely different requests, at different
 * points in time). {@code String} values returned by {@link #path()}, {@link #header(String)},
 * {@link #param(String)}, {@link #query(String)} are independent heap copies and are always safe
 * to retain past the handler.
 *
 * <h3>Dev-mode use-after-recycle guard</h3>
 * When {@link Flash#DEV} is {@code true}, every accessor checks that this instance is still the
 * one currently being handled; a call after the handler has already returned (e.g. from a
 * captured reference in an async callback, a {@link java.util.concurrent.CompletableFuture}
 * continuation, or a background thread) throws {@link IllegalStateException} immediately,
 * loudly, and at the exact call site that misused it — instead of silently reading whatever the
 * next (or a completely different) request happened to reset this instance to. In production
 * this check is a single {@code boolean} field read gated behind a {@code static final} flag the
 * the measured cost.
 */
public class Request {

    private RequestBody body;
    private HeaderView trailers = EmptyHeaderView.INSTANCE;

    /** Internal: the parsed request line (method, path, query, protocol, headers). */
    private RequestLine requestLine;

    private PathParams pathParams;
    private QueryParams queryParams;
    private String cachedPath;

    private InetSocketAddress remoteAddress;
    private SSLSocket sslSocket;

    // unsafe to use further. Only consulted when poisoningEnabled is true (see checkActive()).
    private boolean active;

    // Defaults to the real Flash.DEV value. Flash.DEV is a static final boolean fixed once at
    // JVM startup (from a system property), so no individual test can toggle it — this field
    // exists solely so RequestRecycleGuardTest can exercise the dev-mode branch without a
    // fragile reflective override of a `static final` field. Package-private: only this
    // package's own tests reach for it; production code never touches it.
    private static volatile boolean poisoningEnabled = Flash.DEV;

    /** Test-only override of the dev-mode poisoning check — see the field's own comment. */
    static void setPoisoningEnabledForTesting(boolean enabled) {
        poisoningEnabled = enabled;
    }

    /** Pooled instance, populated later via {@link #reset}. One per connection — see {@link RequestParser}. */
    public Request() {
    }

    /** Test / manual constructor — {@code remoteAddress()} returns {@code null}, {@code isSecure()} is {@code false}. */
    public Request(RequestLine requestLine, byte[] body) {
        reset(requestLine, RequestBody.of(body), null, null);
    }

    /**
     * Repositions this instance over a new request. Package-private: only {@link RequestParser}
     * (same package) calls this — user code never constructs or resets a {@code Request}
     * directly outside the test constructor above.
     */
    void reset(RequestLine requestLine, RequestBody body, InetSocketAddress remoteAddress, SSLSocket sslSocket) {
        this.requestLine   = requestLine;
        this.body          = body;
        this.trailers      = EmptyHeaderView.INSTANCE;
        this.pathParams    = null;
        this.queryParams   = null;
        this.cachedPath    = null;
        this.remoteAddress = remoteAddress;
        this.sslSocket     = sslSocket;
        this.active        = true;
    }

    /**
     * Marks this instance unsafe for further use. Called by the connection driver (e.g.
     * {@code Http1Connection}) once the handler (and any automatic post-handler work, e.g.
     * {@link #drain()}) has finished with it, before the connection loop reuses it for the next
     * request — {@code public} because the connection driver lives in a different package
     * (matching {@link RequestLine#reset}'s own precedent), not because user code should ever
     * call it. A no-op in production beyond the field write — see the class Javadoc's dev-mode
     * guard section.
     */
    public void recycle() {
        this.active = false;
    }

    private void checkActive() {
        if (poisoningEnabled && !active) {
            throw new IllegalStateException(
                    "Request used after the handler returned — do not retain a Request past the "
                    + "handler; copy any String values you need instead");
        }
    }

    /**
     * Internal: injects path parameters after routing. Package-private so only
     * {@link PathParams#inject} (same package) can call it — not user code.
     * Use {@link PathParams#inject(Request, PathParams)} from routing code.
     */
    void setPathParams(PathParams p) { this.pathParams = p; }

    /**
     * Repositions {@code pooled} over a freshly-parsed request. {@code body} is already fully
     * configured by the caller ({@code RequestParser}, which owns and resets its own pooled
     * method's only job is wiring it, {@code requestLine}, and the connection identity fields
     * into {@code pooled}.
     */
    public static Request forParsed(Request pooled, RequestLine requestLine, RequestBody body,
                                    InetSocketAddress remoteAddress, SSLSocket sslSocket) {
        pooled.reset(requestLine, body, remoteAddress, sslSocket);
        return pooled;
    }

    /** Internal protocol hook that supplies the request's trailer collection. */
    public void setTrailers(HeaderView trailers) {
        this.trailers = trailers == null ? EmptyHeaderView.INSTANCE : trailers;
    }

    // ── Request line ──────────────────────────────────────────────────────────

    /** HTTP method ({@code GET}, {@code POST}, …). */
    public HttpMethod method() { checkActive(); return requestLine.getMethod(); }

    /**
     * Request path decoded as UTF-8. Includes a leading slash; never includes the query string.
     * Example: a request for {@code /users/42?page=1} returns {@code "/users/42"}.
     */
    public String path() {
        checkActive();
        if (cachedPath != null) return cachedPath;
        ByteView v = requestLine.getPath();
        // view is a contiguous array slice (always true for h1 today), instead of a byte-at-a-time
        // copy into a scratch array followed by a second allocation for the String itself.
        if (v instanceof ArrayBackedByteView abv) {
            return cachedPath = new String(abv.array(), abv.offset(), v.length(), StandardCharsets.UTF_8);
        }
        byte[] buf = new byte[v.length()];
        for (int i = 0; i < v.length(); i++) buf[i] = v.byteAt(i);
        return cachedPath = new String(buf, StandardCharsets.UTF_8);
    }

    // ── Headers ───────────────────────────────────────────────────────────────

    /**
     * Returns the first value of header {@code name}, or {@code null} if absent.
     * Lookup is case-insensitive ({@code "content-type"} and {@code "Content-Type"} are equivalent).
     */
    public String header(String name) { checkActive(); return requestLine.getHeaders().first(name); }

    /**
     * The first value of header {@code name} as a view over the request's own bytes, or {@code null}:
     * {@link #header} without the {@code String}, for a hot path. Valid only while the handler runs.
     */
    public ByteView headerView(String name) { checkActive(); return requestLine.getHeaders().view(name); }

    /**
     * Returns all values of header {@code name} in declaration order.
     * Useful for headers that appear multiple times (e.g. {@code Accept}, {@code Cookie}).
     * Lookup is case-insensitive. Returns an empty list if the header is absent.
     */
    public List<String> headers(String name) { checkActive(); return requestLine.getHeaders().all(name); }

    /**
     * Returns all header values in declaration order, one entry per header line.
     * Useful for debugging; for targeted access prefer {@link #header(String)}.
     */
    public List<String> headers() { checkActive(); return requestLine.getHeaders().all(); }

    /**
     * The scheme and authority the client addressed, e.g. {@code https://example.com}: the connection's
     * scheme and the {@code Host} header. The client chooses both, so nothing that must not be misled
     * reads this alone; it reads an origin the application configured, falling back to this one.
     */
    public String origin() {
        return (isSecure() ? "https" : "http") + "://" + header("Host");
    }

    /**
     * Returns the value of cookie {@code name}, or {@code null} if the request does not carry it.
     * Reads the {@code Cookie} header in place; only the returned value is allocated.
     */
    public String cookie(String name) {
        String cookies = header("Cookie");
        if (cookies == null) return null;
        for (int at = cookies.indexOf(name); at >= 0; at = cookies.indexOf(name, at + 1)) {
            int eq = at + name.length();
            if (eq < cookies.length() && cookies.charAt(eq) == '='
                    && (at == 0 || cookies.charAt(at - 1) == ' ' || cookies.charAt(at - 1) == ';')) {
                int end = cookies.indexOf(';', eq + 1);
                return cookies.substring(eq + 1, end < 0 ? cookies.length() : end);
            }
        }
        return null;
    }

    // ── Path parameters ───────────────────────────────────────────────────────

    /**
     * Returns the path parameter named {@code name}, or {@code null}.
     * Parameters are declared in the route pattern (e.g. {@code /users/{id}}) and
     * injected by the router before the handler runs. Returns {@code null} if this
     * route has no such parameter or the route is not parametric.
     */
    public String param(String name) { checkActive(); return pathParams != null ? pathParams.get(name) : null; }

    // ── Query parameters ──────────────────────────────────────────────────────

    /**
     * Returns the first query parameter named {@code name}, or {@code null}.
     * The query string is parsed lazily on the first call and cached for the request lifetime.
     * For {@code ?a=1&a=2}, returns {@code "1"}.
     */
    public String query(String name) { checkActive(); return resolveQueryParams().get(name); }

    /**
     * Returns all query parameters named {@code name} in declaration order.
     * For {@code ?tag=a&tag=b}, returns {@code ["a", "b"]}.
     * Returns an empty list if the parameter is absent.
     */
    public List<String> queries(String name) { checkActive(); return resolveQueryParams().getAll(name); }

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
    public InetSocketAddress remoteAddress() { checkActive(); return remoteAddress; }

    // ── TLS ───────────────────────────────────────────────────────────────────

    /** Whether this request arrived over TLS (HTTPS). */
    public boolean isSecure() { checkActive(); return sslSocket != null; }

    /**
     * Returns the TLS session for this connection, or {@code null} for plain HTTP.
     * Gives access to {@link SSLSession#getPeerCertificates()} (mTLS — the caller's certificate
     * chain, if {@code TlsConfig.clientAuth} required or requested one), and to
     * {@link SSLSession#getCipherSuite()} / {@link SSLSession#getProtocol()} for logging and
     * diagnostics. {@code null} rather than throwing when {@link #isSecure()} is {@code false} —
     * check that first, or just null-check the result.
     */
    public SSLSession sslSession() { checkActive(); return sslSocket != null ? sslSocket.getSession() : null; }

    // ── Body ──────────────────────────────────────────────────────────────────

    /**
     * Returns the request body accessor. Use {@link RequestBody#bytes()} to materialise
     * the full body or {@link RequestBody#stream()} for zero-copy streaming access.
     * The two modes are mutually exclusive per request.
     */
    public RequestBody body() { checkActive(); return body; }

    /**
     * Returns request trailers after the body has been consumed completely.
     *
     * @throws IllegalStateException when called before the body reaches EOF
     */
    public HeaderView trailers() {
        checkActive();
        if (!body.fullyRead()) {
            throw new IllegalStateException("request trailers are available only after the body is fully read");
        }
        return trailers;
    }

    /** Discards unread body bytes; called by the server after each request on keep-alive connections. */
    public void drain() { body.drain(); }

    // ── Internal ─────────────────────────────────────────────────────────────

    /** Internal: the parsed request line (method, path, query, protocol, headers). */
    public RequestLine getRequestLine() { checkActive(); return requestLine; }

    /** Internal: path parameters injected by the router, or {@code null} if none matched. */
    public PathParams getPathParams() { checkActive(); return pathParams; }

    /** Internal: case-insensitive header value comparison used by the server keep-alive logic. */
    public boolean headerEquals(String name, String value) {
        checkActive();
        return requestLine.getHeaders().valueEqualsIgnoreCase(name, value);
    }

    private QueryParams resolveQueryParams() {
        if (queryParams == null) {
            ByteView raw = requestLine.getQuery();
            queryParams = raw != null ? new QueryParams(raw) : QueryParams.EMPTY;
        }
        return queryParams;
    }

    @Override
    public String toString() {
        return "Request(method=" + (requestLine != null ? requestLine.getMethod() : null)
                + ", path=" + (requestLine != null ? requestLine.getPath() : null) + ")";
    }
}
