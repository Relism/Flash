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

    public Request(RequestLine requestLine, byte[] body) {
        this(requestLine, LazyBody.of(body));
    }

    public static Request forParsed(RequestLine requestLine, InputStream stream,
                                    int contentLength, byte[] headerBuf,
                                    int bodyStart, int preBufLen) {
        LazyBody lazy = contentLength > 0
                ? new LazyBody(stream, contentLength, headerBuf, bodyStart, preBufLen)
                : LazyBody.empty();
        return new Request(requestLine, lazy);
    }

    public byte[] getBody() { return lazyBody.get(); }

    public String       getHeader(String name)  { return requestLine.getHeaders().getFirst(name); }
    public List<String> getHeaders(String name) { return requestLine.getHeaders().getAll(name); }
    public List<String> getHeaders()            { return requestLine.getHeaders().getAll(); }
    public boolean      headerEquals(String name, String value) { return requestLine.getHeaders().headerValueEqualsIgnoreCase(name, value); }

    public String getPathParam(String name) {
        return pathParams != null ? pathParams.get(name) : null;
    }

    public String getQueryParam(String name) {
        return resolveQueryParams().get(name);
    }

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
