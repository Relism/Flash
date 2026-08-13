package dev.relism.flash.transport;

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
 * <p>Extended in Phase 4 with the router's reusable {@code MatchResult}/path-view fields
 * (currently still {@code ThreadLocal} in {@code FastPathRouterImpl}, per {@code EX-06}'s own
 * multi-phase assignment — see {@code DECISIONS.md} for why Phase 2 does not also absorb that
 * part of the fix) and in later phases with HTTP/2 write/HPACK scratch.
 */
public final class ConnectionScratch {

    /** Matches the relay-buffer size the {@code ThreadLocal} it replaces used. */
    public static final int RELAY_BUFFER_SIZE = 8192;

    /** Large enough for the decimal digits of any {@code long}, including a sign. */
    public static final int DECIMAL_BUFFER_SIZE = 20;

    /** Scratch for {@code Http1ResponseWriter}'s decimal (status code / Content-Length) encoding. */
    public final byte[] decimalBuffer = new byte[DECIMAL_BUFFER_SIZE];

    /** Scratch for relaying a streaming or chunked response body without allocating per response. */
    public final byte[] relayBuffer = new byte[RELAY_BUFFER_SIZE];

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
        // decimalBuffer/relayBuffer need no clearing: every reader of either only ever reads
        // back exactly the region the immediately preceding writer just wrote (writeLong fills
        // from the end backward and reports its own start position; relay() reports its own
        // fill length), so stale bytes from a previous connection are never observed.
    }
}
