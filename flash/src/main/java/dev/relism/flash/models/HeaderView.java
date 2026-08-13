package dev.relism.flash.models;

import dev.relism.fpr.core.ByteView;

import java.util.List;

/**
 * The read-side contract every header container implements, protocol-neutral: {@link
 * Http1HeaderMap} backs it with an HTTP/1.1 byte-buffer range today; a Phase 10
 * {@code Http2HeaderMap} will back it with HPACK-decoded (name, value) pairs. Neither concrete
 * shape leaks into this interface — there is no {@code reset(byte[], int, int)} here, since that
 * signature only makes sense for a byte-range-backed implementation.
 *
 * <p>{@link RequestLine#getHeaders()} is typed as this interface (not a concrete class), which
 * is what lets Phase 10 hand a {@link Request} an HPACK-backed header container without touching
 * a single line of {@code Request}'s own code — the entire point of this phase's refactor (R1:
 * h1 and h2 are peers behind a shared abstraction, never one forking the other).
 *
 * <h3>Lifetime contract</h3>
 * Every implementation lives on the connection (h1) or the stream (h2), not per-request, and is
 * repositioned in place between requests — never retain an instance past the handler that
 * received it. {@code String} values returned by {@link #first}/{@link #all} are safe to retain
 * (independent heap copies); {@link ByteView}s returned by {@link #view} and passed to {@link
 * HeaderConsumer#accept} are not — see each implementation's own Javadoc for its exact reuse
 * window.
 */
public interface HeaderView {

    /** Returns the first value of header {@code name} (case-insensitive), or {@code null}. */
    String first(String name);

    /** Returns all values of header {@code name} in declaration order, or an empty list. */
    List<String> all(String name);

    /** Returns all header values in declaration order. */
    List<String> all();

    /** Returns a view over the first value of {@code name}, or {@code null} — see the implementation's own reuse-window contract. */
    ByteView view(String name);

    /** Case-insensitive comparison of the first value of {@code name} against {@code value}. */
    boolean valueEqualsIgnoreCase(String name, String value);

    /** Whether any header named {@code name} is present. */
    boolean contains(String name);

    /** Total number of header lines (not distinct names — a repeated header counts once per line). */
    int count();

    /**
     * Visits every header in declaration order without allocating a per-header object — see each
     * implementation's Javadoc for exactly which instances are reused and their validity window.
     */
    void forEach(HeaderConsumer consumer);

    /**
     * Callback for {@link #forEach}. Implement with a reusable, field-holding instance (reset
     * before each {@code forEach} call) rather than a capturing lambda if the call site itself
     * needs to be allocation-free too — a capturing lambda is its own per-call allocation, same
     * as anywhere else on a hot path.
     */
    @FunctionalInterface
    interface HeaderConsumer {
        void accept(ByteView name, ByteView value);
    }
}
