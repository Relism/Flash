package dev.relism.flash.http;

import dev.relism.flash.RequestParser;
import dev.relism.flash.bytes.ArrayBackedByteView;
import dev.relism.flash.bytes.Pairs;
import dev.relism.flash.bytes.FastPathViews;
import dev.relism.fpr.core.ByteView;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * An incoming request, read in place from the connection's buffer. One instance per connection
 * (HTTP/1.1) or stream (HTTP/2), repositioned over every request rather than allocated.
 *
 * <pre>{@code
 * app.get("/users/{id}", (req, res) -> {
 *     long id   = req.paramLong("id");            // /users/42  → 42, parsed from the bytes
 *     int  page = req.queryInt("page", 1);        // ?page=2    → 2
 *     String accept = req.header("Accept");
 *     InputStream in = req.body().stream();       // straight off the socket
 * });
 * }</pre>
 *
 * <p><b>Do not keep it</b> past the handler: the same instance describes the next request as soon
 * as this one's handler returns. The {@code String}s it hands out are copies and safe to keep. With
 * {@code ServerConfig.dev} on, any use after the handler returned throws at the offending call.
 */
public class Request {

    private RequestBody body;
    private HeaderView trailers = EmptyHeaderView.INSTANCE;
    private RequestLine requestLine;
    private PathParams pathParams;
    private QueryParams queryParams;
    private QueryParams form;
    private String cachedPath;
    private InetSocketAddress remoteAddress;
    private SSLSocket sslSocket;
    private Set<String> trustedProxies;
    private boolean active;

    /** Pooled instance, positioned by {@link #forParsed}. One per connection — see {@link RequestParser}. */
    public Request() {
    }

    /** A request built by hand, for tests: no connection, no TLS. */
    public Request(RequestLine requestLine, byte[] body) {
        reset(requestLine, RequestBody.of(body), null, null, null);
    }

    void reset(RequestLine requestLine, RequestBody body, InetSocketAddress remoteAddress, SSLSocket sslSocket,
               Set<String> trustedProxies) {
        this.requestLine    = requestLine;
        this.body           = body;
        this.trailers       = EmptyHeaderView.INSTANCE;
        this.pathParams     = null;
        this.queryParams    = null;
        this.form           = null;
        this.cachedPath     = null;
        this.remoteAddress  = remoteAddress;
        this.sslSocket      = sslSocket;
        this.trustedProxies = trustedProxies;
        this.active         = true;
    }

    /**
     * Positions {@code pooled} over a parsed request. {@code trustedProxies} is non-null only when the
     * connection's peer is one of them, which is what lets {@link #clientAddress()} read forwarded headers.
     */
    public static Request forParsed(Request pooled, RequestLine requestLine, RequestBody body,
                                    InetSocketAddress remoteAddress, SSLSocket sslSocket, Set<String> trustedProxies) {
        pooled.reset(requestLine, body, remoteAddress, sslSocket, trustedProxies);
        return pooled;
    }

    /** Marks this instance used up. The connection calls it in development mode only; see the class Javadoc. */
    public void recycle() {
        this.active = false;
    }

    private void checkActive() {
        if (!active) throw new IllegalStateException(
                "Request used after the handler returned: copy the values you need instead of keeping the request");
    }

    void setPathParams(PathParams p) { this.pathParams = p; }

    /** The trailers a protocol read after the body. */
    public void setTrailers(HeaderView trailers) {
        this.trailers = trailers == null ? EmptyHeaderView.INSTANCE : trailers;
    }

    // -- Request line -------------------------------------------------------------

    public HttpMethod method() { checkActive(); return requestLine.getMethod(); }

    /** The path, decoded as UTF-8, without the query: {@code /users/42?page=1} → {@code /users/42}. */
    public String path() {
        checkActive();
        if (cachedPath == null) cachedPath = string(requestLine.getPath(), 0, requestLine.getPath().length());
        return cachedPath;
    }

    /** The raw query string, without the {@code ?}, or {@code null}: bytes, for whoever forwards it as it is. */
    public ByteView rawQuery() { checkActive(); return requestLine.getQuery(); }

    /** The parsed request line: method, path, query, protocol and headers as views. */
    public RequestLine requestLine() { checkActive(); return requestLine; }

    // -- Headers -------------------------------------------------------------------

    /** The first value of header {@code name}, case-insensitively, or {@code null}. */
    public String header(String name) { checkActive(); return requestLine.getHeaders().first(name); }

    /** {@link #header} as a view over the request's own bytes, for a hot path. Valid while the handler runs. */
    public ByteView headerView(String name) { checkActive(); return requestLine.getHeaders().view(name); }

    /** Every value of header {@code name}, in order. */
    public List<String> headers(String name) { checkActive(); return requestLine.getHeaders().all(name); }

    /** Every header line's value, in order: for debugging. */
    public List<String> headers() { checkActive(); return requestLine.getHeaders().all(); }

    /**
     * The scheme and authority the client addressed, e.g. {@code https://example.com}. The client
     * chooses both: anything that must not be misled reads an origin the application configured.
     */
    public String origin() {
        return (isSecure() ? "https" : "http") + "://" + header("Host");
    }

    /**
     * Cookie {@code name}, or {@code null}. Read in place from the {@code Cookie} header; only the
     * value is allocated. HTTP/2 may split cookies across several headers, and all are read.
     */
    public String cookie(String name) {
        ByteView first = headerView("Cookie");
        if (first == null) return null;
        String found = cookieIn(first, name);
        if (found != null) return found;
        List<String> all = requestLine.getHeaders().all("Cookie");
        for (int i = 1; i < all.size(); i++) {
            byte[] value = all.get(i).getBytes(StandardCharsets.UTF_8);
            found = cookieIn(new FastPathViews.RequestByteView(value, 0, value.length), name);
            if (found != null) return found;
        }
        return null;
    }

    private static String cookieIn(ByteView cookies, String name) {
        int n = name.length(), len = cookies.length();
        for (int at = 0; at + n < len; at++) {
            if (at > 0 && cookies.byteAt(at - 1) != ' ' && cookies.byteAt(at - 1) != ';') continue;
            if (cookies.byteAt(at + n) != '=' || !matches(cookies, at, name)) continue;
            int start = at + n + 1, end = start;
            while (end < len && cookies.byteAt(end) != ';') end++;
            return string(cookies, start, end - start);
        }
        return null;
    }

    // -- Path and query parameters -------------------------------------------------

    /** Path parameter {@code name} ({@code /users/{id}}), or {@code null}. */
    public String param(String name) { checkActive(); return pathParams != null ? pathParams.get(name) : null; }

    /** Path parameter {@code name} as a number, parsed from the path's bytes; missing or malformed is a 400. */
    public long paramLong(String name) {
        checkActive();
        long range = pathParams == null ? -1L : pathParams.range(name);
        if (range < 0) throw HttpException.badRequest("Missing path parameter " + name);
        return decimal(pathParams.source(), range, name);
    }

    public int paramInt(String name) {
        return toInt(paramLong(name), name);
    }

    /** The first query parameter {@code name}: {@code ?a=1&a=2} → {@code "1"}; {@code null} when absent. */
    public String query(String name) { checkActive(); return queries().get(name); }

    /** Every query parameter {@code name}, in order. */
    public List<String> queries(String name) { checkActive(); return queries().getAll(name); }

    /** Query parameter {@code name} as a number, or {@code fallback} when absent or empty; malformed is a 400. */
    public long queryLong(String name, long fallback) {
        checkActive();
        QueryParams query = queries();
        long range = query.range(name);
        return range < 0 || Pairs.lo(range) == 0 ? fallback : decimal(query.raw(), range, name);
    }

    public int queryInt(String name, int fallback) {
        return toInt(queryLong(name, fallback), name);
    }

    /**
     * The body as {@code application/x-www-form-urlencoded} fields, parsed in place on first use.
     * Reads the whole body, which the server's body limit bounds.
     */
    public QueryParams form() {
        checkActive();
        if (form == null) {
            byte[] bytes = body.bytes();
            form = new QueryParams(new FastPathViews.RequestByteView(bytes, 0, bytes.length));
        }
        return form;
    }

    // -- Connection ------------------------------------------------------------------

    /** The peer's socket address, or {@code null} for a request built by hand. */
    public InetSocketAddress remoteAddress() { checkActive(); return remoteAddress; }

    /**
     * The client's address. From a trusted proxy ({@code ServerConfig.trustedProxies}), the last
     * {@code X-Forwarded-For} entry that is not a trusted proxy itself; from anyone else, the peer
     * address, since the header is then whatever the client wrote.
     */
    public String clientAddress() {
        checkActive();
        if (trustedProxies != null) {
            ByteView forwarded = headerView("X-Forwarded-For");
            if (forwarded != null) {
                int end = forwarded.length();
                String entry = null;
                while (end > 0) {
                    int start = end;
                    while (start > 0 && forwarded.byteAt(start - 1) != ',') start--;
                    entry = trimmed(forwarded, start, end);
                    if (!trustedProxies.contains(entry)) return entry;
                    end = start - 1;
                }
                if (entry != null) return entry;
            }
        }
        return remoteAddress == null ? null : remoteAddress.getAddress().getHostAddress();
    }

    public boolean isSecure() { checkActive(); return sslSocket != null; }

    /** The TLS session (peer certificates for mTLS, cipher, protocol), or {@code null} over plain HTTP. */
    public SSLSession sslSession() { checkActive(); return sslSocket != null ? sslSocket.getSession() : null; }

    // -- Body ---------------------------------------------------------------------------

    /** The body: {@link RequestBody#stream()} straight off the socket, or {@link RequestBody#bytes()} whole. */
    public RequestBody body() { checkActive(); return body; }

    /**
     * The trailers, once the body has been read to its end.
     *
     * @throws IllegalStateException before that
     */
    public HeaderView trailers() {
        checkActive();
        if (!body.fullyRead()) throw new IllegalStateException("Trailers are available once the body is fully read");
        return trailers;
    }

    /** Discards what the handler left of the body, so the connection can serve its next request. */
    public void drain() { body.drain(); }

    // -- Internals ---------------------------------------------------------------------

    private QueryParams queries() {
        if (queryParams == null) {
            ByteView raw = requestLine.getQuery();
            queryParams = raw != null ? new QueryParams(raw) : QueryParams.EMPTY;
        }
        return queryParams;
    }

    /** An optional sign and up to 18 digits, straight from the bytes. */
    private static long decimal(ByteView bytes, long range, String name) {
        int start = Pairs.hi(range), len = Pairs.lo(range);
        boolean negative = len > 0 && bytes.byteAt(start) == '-';
        int i = negative ? 1 : 0;
        if (len == i || len - i > 18) throw HttpException.badRequest("Not a number: " + name);
        long value = 0;
        for (; i < len; i++) {
            int digit = bytes.byteAt(start + i) - '0';
            if (digit < 0 || digit > 9) throw HttpException.badRequest("Not a number: " + name);
            value = value * 10 + digit;
        }
        return negative ? -value : value;
    }

    private static int toInt(long value, String name) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw HttpException.badRequest("Out of range: " + name);
        return (int) value;
    }

    private static boolean matches(ByteView bytes, int at, String name) {
        for (int i = 0; i < name.length(); i++) if (bytes.byteAt(at + i) != (byte) name.charAt(i)) return false;
        return true;
    }

    private static String trimmed(ByteView bytes, int start, int end) {
        while (start < end && bytes.byteAt(start) == ' ') start++;
        while (end > start && bytes.byteAt(end - 1) == ' ') end--;
        return string(bytes, start, end - start);
    }

    private static String string(ByteView bytes, int start, int len) {
        if (bytes instanceof ArrayBackedByteView array)
            return new String(array.array(), array.offset() + start, len, StandardCharsets.UTF_8);
        byte[] copy = new byte[len];
        for (int i = 0; i < len; i++) copy[i] = bytes.byteAt(start + i);
        return new String(copy, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "Request(method=" + (requestLine != null ? requestLine.getMethod() : null)
                + ", path=" + (requestLine != null ? requestLine.getPath() : null) + ")";
    }
}
