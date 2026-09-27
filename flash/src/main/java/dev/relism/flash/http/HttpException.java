package dev.relism.flash.http;

import java.util.ArrayList;
import java.util.List;

/**
 * A failure that already knows its status. The app's error handler answers it with that status, its
 * headers and a JSON body {@code {"error": message, "status": status}}.
 *
 * <p>It carries no stack trace: it is how a request is refused, not a bug, and a 404 should cost
 * what a return costs.
 *
 * <pre>{@code
 * throw HttpException.notFound("Project");
 * throw HttpException.unauthorized().header("WWW-Authenticate", "Bearer");
 * }</pre>
 */
public class HttpException extends RuntimeException {

    private final int status;
    private List<PreEncodedHeader> headers = List.of();

    public HttpException(int status, String message) {
        super(message, null, false, false);
        this.status = status;
    }

    public int status() { return status; }

    /** Headers the error response carries: a challenge, an {@code Allow}, a {@code Retry-After}. */
    public List<PreEncodedHeader> headers() { return headers; }

    public HttpException header(String name, String value) {
        if (headers.isEmpty()) headers = new ArrayList<>(2);
        headers.add(new PreEncodedHeader(name, value));
        return this;
    }

    public static HttpException badRequest(String message) { return new HttpException(400, message); }

    public static HttpException unauthorized() { return new HttpException(401, "Unauthorized"); }

    public static HttpException forbidden() { return forbidden("Forbidden"); }

    /** Refused with a reason the caller may be told — why the credential is not enough, never what it would take. */
    public static HttpException forbidden(String message) { return new HttpException(403, message); }

    public static HttpException notFound(String what) { return new HttpException(404, what + " not found"); }

    public static HttpException methodNotAllowed(String allow) {
        return new HttpException(405, "Method Not Allowed").header("Allow", allow);
    }

    public static HttpException conflict(String message) { return new HttpException(409, message); }

    public static HttpException unprocessable(String message) { return new HttpException(422, message); }

    public static HttpException tooManyRequests(long retryAfterSeconds) {
        return new HttpException(429, "Too Many Requests").header("Retry-After", Long.toString(retryAfterSeconds));
    }

    public static HttpException internal(String message) { return new HttpException(500, message); }
}
