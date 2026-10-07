package dev.relism.flash.routing;

import dev.relism.flash.http.Exchange;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.PreEncodedHeader;
import dev.relism.fpr.core.ByteView;

import java.util.List;
import java.util.Set;

/**
 * Cross-origin requests, as a middleware. Answers a preflight itself, on any path, before routing
 * decides anything; adds the allow headers to every other request from an allowed origin. Every
 * header is encoded once, here; a request only compares bytes.
 *
 * <pre>{@code
 * app.use(Cors.allow("https://app.example").methods("GET", "POST").headers("Authorization", "Content-Type"));
 * }</pre>
 */
public final class Cors implements Middleware {

    private static final PreEncodedHeader VARY = new PreEncodedHeader("Vary", "Origin");
    private static final PreEncodedHeader ANY_ORIGIN = new PreEncodedHeader("Access-Control-Allow-Origin", "*");
    private static final PreEncodedHeader CREDENTIALS = new PreEncodedHeader("Access-Control-Allow-Credentials", "true");

    private final Set<String> origins;
    private final List<String> methods;
    private final List<String> headers;
    private final boolean credentials;
    private final PreEncodedHeader allowMethods;
    private final PreEncodedHeader allowHeaders;
    private final PreEncodedHeader maxAge = new PreEncodedHeader("Access-Control-Max-Age", "600");

    private Cors(Set<String> origins, List<String> methods, List<String> headers, boolean credentials) {
        if (credentials && origins.isEmpty())
            throw new IllegalArgumentException("Credentials cannot be allowed for any origin: name the origins");
        this.origins = origins;
        this.methods = methods;
        this.headers = headers;
        this.credentials = credentials;
        this.allowMethods = new PreEncodedHeader("Access-Control-Allow-Methods", String.join(", ", methods));
        this.allowHeaders = headers.isEmpty() ? null : new PreEncodedHeader("Access-Control-Allow-Headers", String.join(", ", headers));
    }

    /** Only {@code origins} (scheme and host, e.g. {@code https://app.example}); none named means any. */
    public static Cors allow(String... origins) {
        return new Cors(Set.of(origins), List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"), List.of(), false);
    }

    public Cors methods(String... methods) {
        return new Cors(origins, List.of(methods), headers, credentials);
    }

    public Cors headers(String... headers) {
        return new Cors(origins, methods, List.of(headers), credentials);
    }

    /** Lets a browser send cookies and authorization with the request; needs named origins. */
    public Cors credentials() {
        return new Cors(origins, methods, headers, true);
    }

    @Override
    public void handle(Exchange exchange, Next next) throws Exception {
            var request = exchange.request();
            var response = exchange.response();
            ByteView origin = request.headerView("Origin");
            if (origin == null) { next.handle(exchange); return; }
            String allowed = allowed(origin);
            if (allowed == null) { next.handle(exchange); return; }

            if (origins.isEmpty()) response.header(ANY_ORIGIN);
            else response.header("Access-Control-Allow-Origin", allowed).header(VARY);
            if (credentials) response.header(CREDENTIALS);

            if (request.method() == HttpMethod.OPTIONS && request.headerView("Access-Control-Request-Method") != null) {
                response.header(allowMethods).header(maxAge);
                if (allowHeaders != null) response.header(allowHeaders);
                response.status(204);
                return;
            }
            next.handle(exchange);
    }

    /** The allowed origin that {@code origin} is, or null. */
    private String allowed(ByteView origin) {
        if (origins.isEmpty()) return "*";
        for (String candidate : origins) {
            if (candidate.length() != origin.length()) continue;
            boolean same = true;
            for (int i = 0; same && i < candidate.length(); i++) same = origin.byteAt(i) == (byte) candidate.charAt(i);
            if (same) return candidate;
        }
        return null;
    }
}
