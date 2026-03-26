package dev.relism.ext.oidc;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory {@link OidcSessionStore}.
 *
 * <p>Sessions are lost on restart and not shared across instances. For
 * production deployments with multiple nodes or restart-persistence requirements,
 * supply a custom implementation via {@link OidcConfig.Builder#sessionStore}.
 */
public final class InMemoryOidcSessionStore implements OidcSessionStore {

    private final ConcurrentHashMap<String, OidcSession> store = new ConcurrentHashMap<>();

    @Override public void             save(OidcSession s)  { store.put(s.id(), s); }
    @Override public Optional<OidcSession> find(String id) { return Optional.ofNullable(store.get(id)); }
    @Override public void             delete(String id)    { store.remove(id); }
}
