package dev.relism.models;

import dev.relism.fpr.core.ByteView;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.Value;
import lombok.experimental.NonFinal;

import java.io.InputStream;
import java.util.List;

@Value
@ToString
public class Request {
    RequestLine requestLine;

    @Getter(lombok.AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    LazyBody lazyBody;

    @NonFinal @Setter @Getter PathParams  pathParams;
    @NonFinal @Setter @Getter QueryParams queryParams;

    private Request(RequestLine requestLine, LazyBody lazyBody) {
        this.requestLine = requestLine;
        this.lazyBody    = lazyBody;
        this.pathParams  = null;
        this.queryParams = null;
    }

    /** Convenience constructor for test mocks and pre-materialized bodies. */
    public Request(RequestLine requestLine, byte[] body) {
        this(requestLine, LazyBody.of(body));
    }

    /**
     * Factory used by {@link dev.relism.RequestParser}: creates a request whose body is read
     * from {@code stream} on the first call to {@link #getBody()}.
     *
     * @param preBufLen bytes already buffered past the header end (from the 8 KB read-ahead)
     */
    public static Request forParsed(RequestLine requestLine, InputStream stream,
                                    int contentLength, byte[] headerBuf,
                                    int bodyStart, int preBufLen) {
        LazyBody lazy = contentLength > 0
                ? new LazyBody(stream, contentLength, headerBuf, bodyStart, preBufLen)
                : LazyBody.empty();
        return new Request(requestLine, lazy);
    }

    /** Materializes and returns the request body, reading from the socket if not yet done. */
    public byte[] getBody() { return lazyBody.get(); }

    // --- Header access (lazy: String allocated only on call) ---

    public String       getHeader(String name)  { return requestLine.getHeaders().getFirst(name); }
    public List<String> getHeaders(String name) { return requestLine.getHeaders().getAll(name); }
    public List<String> getHeaders()            { return requestLine.getHeaders().getAll(); }

    // --- Path param access ---

    /** Lazy: decodes the named path parameter to a {@code String}. */
    public String getPathParam(String name) {
        return pathParams != null ? pathParams.get(name) : null;
    }

    // --- Query param access ---

    /** Lazy: decodes the first value of {@code name}, or {@code null} if absent. */
    public String getQueryParam(String name) {
        return resolveQueryParams().get(name);
    }

    /** Lazy: decodes all values of {@code name} (e.g. {@code ?tag=a&tag=b}). */
    public List<String> getQueryParams(String name) {
        return resolveQueryParams().getAll(name);
    }

    private QueryParams resolveQueryParams() {
        if (queryParams == null) {
            ByteView raw = requestLine.getQuery();
            queryParams = raw != null ? new QueryParams(raw) : QueryParams.EMPTY;
        }
        return queryParams;
    }
}
