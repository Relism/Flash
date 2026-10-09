package dev.relism.flash.http;

/**
 * A failure a duplex handler has already reported to its peer, in its own protocol: gRPC trailers,
 * say. Thrown so the scopes around the handler ({@link Exchange#around}) see the work fail and a
 * transaction rolls back; {@link Exchange#execute()} then drops it, since the peer has its answer.
 * Unchecked, so a scope that wraps checked exceptions lets it through as it is.
 */
public final class AnsweredFailure extends RuntimeException {

    public AnsweredFailure(Throwable cause) {
        super(cause.toString(), cause, false, false);
    }
}
