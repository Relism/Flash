package dev.relism.flash.routing;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.PreEncodedHeader;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.template.ErrorPages;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * What an app answers when nobody said otherwise. In development: HTML pages with the stack trace.
 * Otherwise: {@code {"error", "status"}} JSON, and nothing internal leaves the server.
 */
@Slf4j
public final class Errors {

    private static final byte[] NOT_FOUND = "{\"error\":\"Not Found\",\"status\":404}".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INTERNAL = "{\"error\":\"Internal Server Error\",\"status\":500}".getBytes(StandardCharsets.US_ASCII);

    private Errors() {}

    /** An {@link HttpException} is answered as it says; anything else is a logged 500. */
    public static ErrorHandler handler(boolean dev) {
        return (failure, request, response) -> {
            if (failure instanceof HttpException refused) return render(refused, response);
            log.error("Unhandled exception in {} {}", request.method(), request.path(), failure);
            response.status(500);
            if (dev) {
                response.type(ContentType.TEXT_HTML);
                return ErrorPages.renderException(request, failure);
            }
            response.type(ContentType.JSON);
            return INTERNAL;
        };
    }

    public static Handler notFound(boolean dev) {
        return dev
                ? (request, response) -> {
                    response.status(404).type(ContentType.TEXT_HTML);
                    return ErrorPages.renderNotFound(request);
                }
                : (request, response) -> {
                    response.status(404).type(ContentType.JSON);
                    return NOT_FOUND;
                };
    }

    /**
     * Places the application's error handler inside infrastructure that must observe its final
     * response. The router keeps the same recovery as a last resort for failures outside this
     * boundary.
     */
    public static Handler boundary(Handler next, ErrorHandler errors) {
        return (request, response) -> {
            try {
                return next.handle(request, response);
            } catch (Exception failure) {
                return recover(errors, failure, request, response);
            }
        };
    }

    static Object recover(ErrorHandler errors, Exception failure, Request request, Response response) {
        try {
            return errors.handle(failure, request, response);
        } catch (Exception broken) {
            log.error("The error handler failed on {} {}", request.method(), request.path(), broken);
            response.status(500).type(ContentType.JSON);
            return INTERNAL;
        }
    }

    /** The status, the headers and {@code {"error", "status"}} of {@code refused}. */
    public static byte[] render(HttpException refused, Response response) {
        response.status(refused.status()).type(ContentType.JSON);
        for (PreEncodedHeader header : refused.headers()) response.header(header);
        String message = refused.getMessage() == null ? "" : refused.getMessage();
        StringBuilder out = new StringBuilder(32 + message.length()).append("{\"error\":\"");
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 15, 16));
                    else out.append(c);
                }
            }
        }
        return out.append("\",\"status\":").append(refused.status()).append('}').toString().getBytes(StandardCharsets.UTF_8);
    }
}
