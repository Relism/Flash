package dev.relism.flash.ext.webdav.internal;

import dev.relism.flash.ext.webdav.DavPath;
import dev.relism.flash.http.HttpException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The write locks of one space. A lock covers its root, and every member below it when its depth is
 * infinite. Not thread-safe: it is read and changed only while its {@link Space} is held. Expiry is
 * wall-clock time, so every process sharing the space reads it alike.
 */
public final class Locks {

    /** The most locks one resource may carry; shared locks are the only way to reach it. */
    public static final int MAX_PER_RESOURCE = 32;
    /** The most locks one space may carry. */
    public static final int MAX = 1024;

    /**
     * @param owner the client's {@code DAV:owner} content, as XML, or {@code ""}
     * @param seconds the timeout granted
     * @param expiresAt epoch milliseconds
     */
    public record Lock(String token, DavPath root, boolean exclusive, boolean deep, String owner, long seconds, long expiresAt) {

        public boolean covers(DavPath path) {
            return root.equals(path) || deep && root.contains(path);
        }

        public boolean within(DavPath path) {
            return path.contains(root);
        }
    }

    // ponytail: every lookup scans all locks; an index by path when a space holds thousands
    private final Map<String, Lock> byToken = new LinkedHashMap<>();
    private boolean changed;

    public Lock get(String token) {
        return byToken.get(token);
    }

    /** The locks on {@code path} or on an ancestor with infinite depth. */
    public List<Lock> covering(DavPath path) {
        List<Lock> found = new ArrayList<>();
        for (Lock lock : byToken.values()) if (lock.covers(path)) found.add(lock);
        return found;
    }

    /** The locks rooted at {@code path} or below it. */
    public List<Lock> within(DavPath path) {
        List<Lock> found = new ArrayList<>();
        for (Lock lock : byToken.values()) if (lock.within(path)) found.add(lock);
        return found;
    }

    public List<Lock> all() {
        return byToken.isEmpty() ? List.of() : List.copyOf(byToken.values());
    }

    /** A new lock, or {@code null} when one already held conflicts with it. */
    public Lock acquire(DavPath path, boolean exclusive, boolean deep, String owner, long seconds) {
        int onPath = 0;
        for (Lock held : byToken.values()) {
            boolean overlaps = held.covers(path) || deep && held.within(path);
            if (overlaps && (held.exclusive || exclusive)) return null;
            if (held.root.equals(path)) onPath++;
        }
        if (onPath >= MAX_PER_RESOURCE) throw new HttpException(503, "a resource holds at most " + MAX_PER_RESOURCE + " locks");
        if (byToken.size() >= MAX) throw new HttpException(503, "a space holds at most " + MAX + " locks");
        return put(new Lock("urn:uuid:" + UUID.randomUUID(), path, exclusive, deep, owner, seconds, expiry(seconds)));
    }

    public Lock refresh(Lock lock, long seconds) {
        return put(new Lock(lock.token, lock.root, lock.exclusive, lock.deep, lock.owner, seconds, expiry(seconds)));
    }

    public void release(String token) {
        changed |= byToken.remove(token) != null;
    }

    /** Drops the locks of a resource that is gone, and of everything below it. */
    public void releaseWithin(DavPath path) {
        changed |= byToken.values().removeIf(lock -> lock.within(path));
    }

    public void prune(long now) {
        if (!byToken.isEmpty()) changed |= byToken.values().removeIf(lock -> now >= lock.expiresAt);
    }

    /** Whether the table changed since it was {@link #load loaded} or {@link #saved}. */
    public boolean changed() {
        return changed;
    }

    public void saved() {
        changed = false;
    }

    /** Replaces the table with the one another process wrote. */
    public void load(List<Lock> locks) {
        byToken.clear();
        for (Lock lock : locks) byToken.put(lock.token, lock);
        changed = false;
    }

    private Lock put(Lock lock) {
        byToken.put(lock.token, lock);
        changed = true;
        return lock;
    }

    private static long expiry(long seconds) {
        return System.currentTimeMillis() + seconds * 1000;
    }
}
