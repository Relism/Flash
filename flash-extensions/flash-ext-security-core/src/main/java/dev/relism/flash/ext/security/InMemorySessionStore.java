package dev.relism.flash.ext.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One instance's sessions, lost on restart. An expired session found on a request is renewed or
 * deleted there, and the rest are swept once every {@value #SWEEP_EVERY} saves, so a sign-in costs
 * a map put rather than a scan of every session.
 */
public final class InMemorySessionStore implements SessionStore {

    private static final int SWEEP_EVERY = 1024;

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger saves = new AtomicInteger();

    @Override
    public void save(Session session) {
        sessions.put(session.id(), session);
        if (saves.incrementAndGet() % SWEEP_EVERY == 0) {
            long now = System.currentTimeMillis();
            sessions.values().removeIf(s -> s.expiresAt().toEpochMilli() <= now);
        }
    }

    /** An expired session is still returned, for a refresher to renew; the caller deletes it otherwise. */
    @Override
    public Session find(String id) {
        return sessions.get(id);
    }

    @Override
    public void delete(String id) {
        sessions.remove(id);
    }
}
