package dev.relism.flash.http;

import dev.relism.fpr.core.ByteView;

import java.util.List;

/**
 * The read-side, protocol-neutral contract every header container implements. HTTP/1.1 uses a
 * byte-range-backed implementation; HTTP/2 uses HPACK-decoded name/value pairs. Neither concrete
 * representation leaks into this interface.
 *
 * <p>{@link RequestLine#getHeaders()} exposes this interface rather than a protocol-specific
 * implementation so request handling remains independent of the transport protocol.
 *
 * <h3>Lifetime contract</h3>
 * Every implementation lives on the connection (HTTP/1.1) or the stream (HTTP/2), not per-request, and is
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
