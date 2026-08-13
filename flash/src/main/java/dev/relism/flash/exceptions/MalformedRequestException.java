package dev.relism.flash.exceptions;

/**
 * Thrown by the HTTP/1.1 parser when a request violates a protocol rule that must be rejected
 * outright — most importantly the request-smuggling defenses of RFC 9112 §6.1 (see
 * {@code EX-02}/{@code EX-03} in {@code flash/docs/http2/IMPLEMENTATION-PLAN.md}) and the hard
 * safety limits in {@code Http1Limits} (see {@code EX-08}).
 *
 * <p>Distinct from {@link HttpException}, which a <em>handler</em> throws to describe an
 * application-level failure and which is routed through the user's configured exception
 * handler ({@code AbstractRouter.getExceptionHandler()}). A malformed request never reaches a
 * handler, or middleware, or the user's exception handler at all: it is rejected by the
 * transport itself, with a fixed, minimal, non-customizable response, and the connection is
 * always closed afterwards — never kept alive. Keeping a connection alive after a rejected
 * request is exactly the situation a smuggling attempt exploits (a rejected first request
 * hiding a crafted second one in the same TCP stream), so the transport never offers that
 * choice to user code.
 */
public class MalformedRequestException extends HttpException {

    public MalformedRequestException(int status, String message) {
        super(status, message);
    }
}
