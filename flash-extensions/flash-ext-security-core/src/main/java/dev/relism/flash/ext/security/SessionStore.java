package dev.relism.flash.ext.security;

/** Where sessions live. {@link InMemorySessionStore} unless one that survives restarts is configured. */
public interface SessionStore {

    void save(Session session);

    /** The session, or {@code null}. */
    Session find(String id);

    void delete(String id);
}
