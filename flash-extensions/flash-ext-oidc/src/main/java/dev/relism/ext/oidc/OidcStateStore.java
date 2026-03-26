package dev.relism.ext.oidc;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived store mapping state nonces → (original URL, PKCE verifier).
 *
 * <p>Entries expire after {@value #TTL_SECONDS} seconds. Cleanup runs on every
 * access to prevent unbounded growth without needing a background thread.
 */
final class OidcStateStore {

    static final int TTL_SECONDS = 600; // 10 minutes

    record Entry(String originalUrl, String codeVerifier, String nonce, Instant expiresAt) {}

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    void put(String state, String originalUrl, String codeVerifier, String nonce) {
        cleanup();
        store.put(state, new Entry(originalUrl, codeVerifier, nonce,
                Instant.now().plusSeconds(TTL_SECONDS)));
    }

    /** Atomically retrieves and removes the entry; returns empty if absent or expired. */
    Optional<Entry> consumeAndRemove(String nonce) {
        cleanup();
        Entry e = store.remove(nonce);
        if (e == null || Instant.now().isAfter(e.expiresAt())) return Optional.empty();
        return Optional.of(e);
    }

    private void cleanup() {
        Instant now = Instant.now();
        store.entrySet().removeIf(kv -> now.isAfter(kv.getValue().expiresAt()));
    }
}
