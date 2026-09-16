package dev.relism.flash.ext.security;

import java.util.concurrent.ConcurrentHashMap;

/** One instance's sessions, lost on restart. Expired ones are swept whenever a session is saved. */
public final class InMemorySessionStore implements SessionStore {

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(Session session) {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> s.expiresAt().toEpochMilli() <= now);
        sessions.put(session.id(), session);
    }

    @Override
    public Session find(String id) {
        return sessions.get(id);
    }

    @Override
    public void delete(String id) {
        sessions.remove(id);
    }
}
