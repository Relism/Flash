package dev.relism.flash.http;

import dev.relism.flash.bytes.FastPathViews;
import dev.relism.fpr.core.ByteView;
import dev.relism.flash.http.HttpMethod;
import java.nio.charset.StandardCharsets;

/**
 * The parsed request line plus headers: method, path, optional query, optional protocol token,
 * and the header container. Internal — reached via {@link Request#requestLine()}, not
 * user-facing API.
 *
 * One instance per connection, repositioned via {@link #reset} for every request rather than
 * reallocated — {@code RequestParser} owns it exactly the way it owns {@link Http1HeaderMap}.
 * {@link #reset} is {@code public} rather than package-private — matching
 * {@link Http1HeaderMap#reset}'s and {@link PathParams#reset}'s own precedent — because
 * {@code RequestParser} (the owner and sole caller) lives in a different package
 * ({@code dev.relism.flash}, not {@code dev.relism.flash.http}). The public constructor below
 * remains for test/manual construction and simply delegates to {@link #reset}.
 *
 * <h3>{@code protocol} is optional</h3>
 * HTTP/1.1 always has a protocol token on the wire ({@code "HTTP/1.1"}); HTTP/2 has no equivalent
 * — a stream's version is implicit in which connection it belongs to. {@link #getProtocol()} may
 * be {@code null} for a header container built by a future non-h1 implementation; h1 always
 * supplies a non-null value today.
 *
 * <h3>The method is a token</h3>
 * Any token RFC 9110 allows is a method. {@link #getMethodToken()} is the token as it arrived;
 * {@link #getMethod()} is its {@link HttpMethod}, or {@code null} for one outside that enum.
 */
public class RequestLine {
    private static final ByteView[] TOKENS = tokens();

    private HttpMethod method;
    private ByteView methodToken;
    private ByteView path;
    private ByteView query;
    private ByteView protocol;
    private HeaderView headers;

    /** Pooled instance, populated later via {@link #reset}. */
    public RequestLine() {
    }

    /** Test / manual construction — delegates to {@link #reset}. */
    public RequestLine(HttpMethod method, ByteView path, ByteView query, ByteView protocol, HeaderView headers) {
        reset(method, path, query, protocol, headers);
    }

    /** Repositions this instance over a new request of a method in {@link HttpMethod}. */
    public void reset(HttpMethod method, ByteView path, ByteView query, ByteView protocol, HeaderView headers) {
        reset(method, method == null ? null : TOKENS[method.ordinal()], path, query, protocol, headers);
    }

    /**
     * Repositions this instance over a new request. See the class Javadoc for why this is {@code public}.
     *
     * @param method the token's {@link HttpMethod}, or {@code null} when it is none of them
     */
    public void reset(HttpMethod method, ByteView methodToken, ByteView path, ByteView query, ByteView protocol,
                      HeaderView headers) {
        this.method   = method;
        this.methodToken = methodToken;
        this.path     = path;
        this.query    = query;
        this.protocol = protocol;
        this.headers  = headers;
    }

    /** The method, or {@code null} for a token outside {@link HttpMethod}. */
    public HttpMethod getMethod() { return method; }

    /** The method token as it arrived. */
    public ByteView getMethodToken() { return methodToken; }

    /** The method token as text; built only for a token outside {@link HttpMethod}. */
    public String getMethodName() {
        if (method != null) return method.name();
        if (methodToken == null) return null;
        byte[] bytes = new byte[methodToken.length()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = methodToken.byteAt(i);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
    public ByteView getPath() { return path; }

    /** Raw query string bytes (after {@code ?}), or {@code null} if the URI has no query string. */
    public ByteView getQuery() { return query; }

    /** The wire protocol token (e.g. {@code "HTTP/1.1"}), or {@code null} — see the class Javadoc. */
    public ByteView getProtocol() { return protocol; }

    public HeaderView getHeaders() { return headers; }

    @Override
    public String toString() {
        return "RequestLine(method=" + getMethodName() + ", path=" + path + ")";
    }

    private static ByteView[] tokens() {
        HttpMethod[] methods = HttpMethod.values();
        ByteView[] tokens = new ByteView[methods.length];
        for (HttpMethod each : methods) tokens[each.ordinal()] = new FastPathViews.SocketByteView(each.getBytes());
        return tokens;
    }
}
