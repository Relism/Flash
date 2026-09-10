package dev.relism.flash.ext.auth;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory {@link SessionStore}.
 *
 * <p>Sessions are lost on restart and not shared across instances. For
 * production deployments with multiple nodes or restart-persistence requirements,
 * supply another implementation to whichever {@link CredentialSource} owns the session.
 */
public final class InMemorySessionStore implements SessionStore {

    private final ConcurrentHashMap<String, Session> store = new ConcurrentHashMap<>();

    @Override public void             save(Session s)  { store.put(s.id(), s); }
    @Override public Optional<Session> find(String id) { return Optional.ofNullable(store.get(id)); }
    @Override public void             delete(String id)    { store.remove(id); }
}
