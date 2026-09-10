package dev.relism.flash.ext.auth;

import java.util.Optional;

/**
 * Where {@link Session}s live between requests. {@link InMemorySessionStore} is the default;
 * supply another for Redis, JDBC, or anything that survives a restart or spans instances.
 */
public interface SessionStore {
    void save(Session session);
    Optional<Session> find(String sessionId);
    void delete(String sessionId);
}
