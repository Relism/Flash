package dev.relism.flash.http;

/**
 * A request that breaks a protocol rule or a configured limit: request smuggling ambiguity
 * (RFC 9112 §6.1), an oversized line, header or body.
 *
 * <p>Thrown while the head is parsed, it never reaches a handler: the transport answers with a
 * minimal response and closes the connection, because keeping it alive after a disputed request
 * boundary is exactly what a smuggling attempt needs. Thrown later, while a handler reads a
 * chunked body past the body limit, it reaches the app's error handler like any
 * {@link HttpException}, with its status.
 */
public class MalformedRequestException extends HttpException {

    public MalformedRequestException(int status, String message) {
        super(status, message);
    }
}
