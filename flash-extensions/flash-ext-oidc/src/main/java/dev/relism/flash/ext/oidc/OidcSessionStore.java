package dev.relism.flash.ext.oidc;

import java.util.Optional;

/**
 * Backing store for {@link OidcSession} objects. The default implementation is
 * {@link InMemoryOidcSessionStore}; supply a custom one via
 * {@link OidcConfig.Builder#sessionStore(OidcSessionStore)} for Redis, JDBC, etc.
 */
public interface OidcSessionStore {
    void save(OidcSession session);
    Optional<OidcSession> find(String sessionId);
    void delete(String sessionId);
}
