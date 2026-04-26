package dev.relism.flash.exceptions;

/**
 * Runtime exception carrying an HTTP status code. Extensions map this to a
 * structured JSON error response via the global exception handler installed by
 * {@code flash-ext-jackson}.
 *
 * <pre>{@code
 * throw HttpException.notFound("Blog");
 * throw new HttpException(422, "Validation failed: title is required");
 * }</pre>
 */
public class HttpException extends RuntimeException {

    private final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static HttpException badRequest(String message) {
        return new HttpException(400, message);
    }

    public static HttpException unauthorized() {
        return new HttpException(401, "Unauthorized");
    }

    public static HttpException forbidden() {
        return new HttpException(403, "Forbidden");
    }

    public static HttpException notFound(String what) {
        return new HttpException(404, what + " not found");
    }

    public static HttpException conflict(String message) {
        return new HttpException(409, message);
    }

    public static HttpException unprocessable(String message) {
        return new HttpException(422, message);
    }

    public static HttpException internal(String message) {
        return new HttpException(500, message);
    }
}
