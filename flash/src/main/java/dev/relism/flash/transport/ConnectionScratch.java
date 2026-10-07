package dev.relism.flash.transport;

import dev.relism.flash.bytes.ByteWriter;

/**
 * Per-connection scratch that would otherwise be a {@link ThreadLocal}: the decimal-formatting
 * scratch and the streaming relay buffer, which a duplex response also reads its request body into.
 *
 * <h3>Why not {@code ThreadLocal}</h3>
 * {@code ThreadLocal} is the right idiom for a bounded platform-thread pool, where "one per
 * thread" means "one per core". Flash runs one <em>virtual</em> thread per connection
 * ({@code Executors.newVirtualThreadPerTaskExecutor()}), so a {@code ThreadLocal} here means
 * one per connection, not one per core — with no upper bound. At 100 000 concurrent
 * connections, an 8 KB relay buffer alone is ~800 MB of memory that a bounded pool would
 * instead cap. {@code ConnectionScratch} is therefore explicit and pooled ({@link ScratchPool}),
 * not thread-local.
 *
 * <h3>Lifetime and thread-safety contract</h3>
 * Allocated once per connection (or reused from {@link ScratchPool}), owned exclusively by the
 * single virtual thread driving that connection for its whole lifetime, and returned to the
 * pool when the connection closes. Never shared between two connections at once — there is no
 * synchronization here because none is needed.
 *
 * The router's matching state is not here: {@code routing} does not depend on {@code transport},
 * so the connection holds a {@code Router.Scratch} of its own.
 */
public final class ConnectionScratch {

    /** Matches the relay-buffer size the {@code ThreadLocal} it replaces used. */
    public static final int RELAY_BUFFER_SIZE = 8192;

    /** Initial capacity for {@link #responseHead}; grows on demand like any {@link ByteWriter}. */
    public static final int RESPONSE_HEAD_INITIAL_SIZE = 1024;

    /** Scratch for relaying a streaming or chunked response body without allocating per response. */
    public final byte[] relayBuffer = new byte[RELAY_BUFFER_SIZE];

    /**
     * The buffer an HTTP/1.1 response head is assembled in
     * (status line, {@code Content-Type}, {@code Date}, custom headers, {@code Content-Length}/
     * {@code Connection}, and — for small fixed bodies — the body itself) into before issuing a
     * single bulk {@code write()}, instead of ~10 small {@code OutputStream.write} calls.
     */
    public final ByteWriter responseHead = new ByteWriter(RESPONSE_HEAD_INITIAL_SIZE);

    /** Called by {@link ScratchPool} before handing a reused instance to a new connection. */
    void reset() {
        responseHead.reset();
        // relayBuffer needs no clearing: every reader only ever reads back exactly the region
        // the immediately preceding relay() call reports it filled, so stale bytes from a
        // previous connection are never observed.
    }
}
