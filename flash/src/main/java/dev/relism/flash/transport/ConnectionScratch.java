package dev.relism.flash.transport;

import dev.relism.flash.bytes.ByteWriter;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The {@code EX-06} fix. Owns every per-connection reusable buffer that used to live in a
 * {@link ThreadLocal} on {@code HttpServer}: the decimal-formatting scratch, the streaming
 * relay buffer, and the WebSocket-handshake {@link MessageDigest}.
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
 * <p>{@code EX-06}'s router half (the {@code FastPathRouterImpl}/{@code FastPathWsRouterImpl}
 * {@code ThreadLocal}s) is fixed in Phase 4, but deliberately <em>not</em> by extending this
 * class: {@code routing} has no dependency on {@code transport} today, and folding the router's
 * scratch fields in here would have created one — see {@code DECISIONS.md}, {@code DEC-19}, for
 * the opaque-per-connection-object mechanism ({@code AbstractRouter#newScratch}) used instead.
 * This class gains HTTP/2 write/HPACK scratch in later phases, where {@code h2} already depends
 * on {@code transport} and no such boundary concern applies.
 */
public final class ConnectionScratch {

    /** Matches the relay-buffer size the {@code ThreadLocal} it replaces used. */
    public static final int RELAY_BUFFER_SIZE = 8192;

    /** Initial capacity for {@link #responseHead}; grows on demand like any {@link ByteWriter}. */
    public static final int RESPONSE_HEAD_INITIAL_SIZE = 1024;

    /** Scratch for relaying a streaming or chunked response body without allocating per response. */
    public final byte[] relayBuffer = new byte[RELAY_BUFFER_SIZE];

    /**
     * {@code EX-27}: the scratch {@code Http1ResponseWriter} serializes a whole response head
     * (status line, {@code Content-Type}, {@code Date}, custom headers, {@code Content-Length}/
     * {@code Connection}, and — for small fixed bodies — the body itself) into before issuing a
     * single bulk {@code write()}, instead of ~10 small {@code OutputStream.write} calls.
     */
    public final ByteWriter responseHead = new ByteWriter(RESPONSE_HEAD_INITIAL_SIZE);

    /** Scratch for the WebSocket handshake's {@code Sec-WebSocket-Accept} SHA-1 digest. */
    public final MessageDigest sha1;

    ConnectionScratch() {
        try {
            this.sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            // Every JDK ships SHA-1 — this is a broken-runtime condition, not a request-time one.
            throw new IllegalStateException("SHA-1 MessageDigest unavailable", e);
        }
    }

    /** Called by {@link ScratchPool} before handing a reused instance to a new connection. */
    void reset() {
        sha1.reset();
        responseHead.reset();
        // relayBuffer needs no clearing: every reader only ever reads back exactly the region
        // the immediately preceding relay() call reports it filled, so stale bytes from a
        // previous connection are never observed.
    }
}
