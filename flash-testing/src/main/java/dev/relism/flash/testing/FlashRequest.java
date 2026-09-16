package dev.relism.flash.testing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A request being built against a {@link FlashTest} server. The HTTP verb is terminal — it
 * sends the request and hands back a {@link FlashResponse}:
 *
 * <pre>{@code
 * app.request()
 *    .header("Authorization", "Bearer " + token)
 *    .json("{\"name\":\"bob\"}")
 *    .post("/api/users")
 *    .expectStatus(201);
 * }</pre>
 *
 * For a bodyless {@code GET} or {@code DELETE}, {@link FlashTest#get} and
 * {@link FlashTest#delete} skip the builder entirely.
 */
public final class FlashRequest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final FlashTest server;
    private final HttpRequest.Builder request = HttpRequest.newBuilder().timeout(REQUEST_TIMEOUT);
    private byte[] body;

    FlashRequest(FlashTest server) {
        this.server = server;
    }

    /** Adds a header. Repeatable — a name may be sent more than once. */
    public FlashRequest header(String name, String value) {
        request.header(name, value);
        return this;
    }

    /** Applies {@code customizer} — how an extension's test kit attaches a credential, for instance. */
    public FlashRequest with(Consumer<FlashRequest> customizer) {
        customizer.accept(this);
        return this;
    }

    /** Sets a UTF-8 request body. */
    public FlashRequest body(String text) {
        this.body = Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8);
        return this;
    }

    /** Sets a raw request body. */
    public FlashRequest body(byte[] bytes) {
        this.body = Objects.requireNonNull(bytes, "bytes").clone();
        return this;
    }

    /** Sets a UTF-8 body and {@code content-type: application/json}. */
    public FlashRequest json(String json) {
        return body(json).header("content-type", "application/json");
    }

    public FlashResponse get(String path)     { return send("GET", path); }
    public FlashResponse post(String path)    { return send("POST", path); }
    public FlashResponse put(String path)     { return send("PUT", path); }
    public FlashResponse patch(String path)   { return send("PATCH", path); }
    public FlashResponse delete(String path)  { return send("DELETE", path); }
    public FlashResponse head(String path)    { return send("HEAD", path); }
    public FlashResponse options(String path) { return send("OPTIONS", path); }

    /** Sends any method, including the ones Flash adds beyond RFC 9110 ({@code PURGE}, {@code QUERY}). */
    public FlashResponse send(String method, String path) {
        URI target = server.baseUri().resolve(normalise(path));
        HttpRequest built = request.uri(target)
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<String> response =
                    server.client().send(built, HttpResponse.BodyHandlers.ofString());
            return new FlashResponse(method, target.getPath(), response);
        } catch (IOException failure) {
            throw new AssertionError(method + ' ' + target + " failed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(method + ' ' + target + " was interrupted", interrupted);
        }
    }

    /** Shared with {@link FlashTest#ws} — the ws:// URI needs the same leading slash. */
    static String normalise(String path) {
        Objects.requireNonNull(path, "path");
        return path.startsWith("/") ? path : '/' + path;
    }
}
