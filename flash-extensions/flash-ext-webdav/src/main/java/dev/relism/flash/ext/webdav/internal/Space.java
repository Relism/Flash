package dev.relism.flash.ext.webdav.internal;

import dev.relism.flash.ext.webdav.DavPath;
import dev.relism.flash.ext.webdav.DavStorage;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * What the handler keeps for one storage: the bytes its finished files hold, the bytes uploads have
 * taken ahead of writing them, and its locks. It is read and changed only between {@link #enter} and
 * {@link #close}, by one thread at a time; that is where a request checks what it may do and applies
 * it, so two requests never act on the same state.
 *
 * <p>This one lives in memory, for a storage one process serves. The NIO engine's clustered directories
 * keep the same in {@code .dav}, for several processes, by overriding the hooks below.
 */
public class Space implements AutoCloseable {

    private final ReentrantLock mutex = new ReentrantLock();
    private final DavStorage storage;
    private final Locks locks = new Locks();
    /** The bytes of finished files; {@code -1} until counted. */
    protected long committed = -1;
    /** The bytes this process's uploads have taken and not yet given back. */
    protected long reserved;

    public Space(DavStorage storage) {
        this.storage = storage;
    }

    /** Holds the space, the state up to date, until {@link #close}. A thread that holds it may enter again. */
    // ponytail: reads hold it alone too, and a deep DELETE or MOVE holds it until done; a shared hold for reads if they contend
    public final Space enter() throws IOException {
        mutex.lock();
        if (mutex.getHoldCount() > 1) return this;
        try {
            load();
        } catch (IOException | RuntimeException failure) {
            mutex.unlock();
            throw failure;
        }
        locks.prune(System.currentTimeMillis());
        return this;
    }

    @Override
    public final void close() throws IOException {
        try {
            if (mutex.getHoldCount() == 1) unload();
        } finally {
            mutex.unlock();
        }
    }

    public final Locks locks() {
        if (!mutex.isHeldByCurrentThread()) throw new IllegalStateException("the locks of a space are read while it is held");
        return locks;
    }

    /** The bytes in use: finished files, counted the first time they are needed, and every upload's share. */
    public final long used() throws IOException {
        if (committed < 0) committed = storage.usage(DavPath.ROOT);
        return committed + reserved + others();
    }

    /** Takes {@code bytes} for an upload when they fit under {@code limit}; whether they did. */
    public final boolean reserve(long bytes, long limit) throws IOException {
        if (used() + bytes > limit) return false;
        reserved += bytes;
        return true;
    }

    public final void release(long bytes) {
        reserved -= bytes;
    }

    /** Counts a change of the finished files, once they are counted. */
    public final void settle(long bytes) {
        if (committed >= 0) committed += bytes;
    }

    /** Reads the state when the space is entered. */
    protected void load() throws IOException {}

    /** Writes the state back when the space is left. */
    protected void unload() throws IOException {}

    /** The bytes other processes' uploads have taken. */
    protected long others() {
        return 0;
    }

    /** Marks this process as changing files until {@link #end}: called before the first change. */
    public void begin() throws IOException {}

    public void end() {}

    /** Gives back what processes that died held; whether there was any. */
    public boolean recover() throws IOException {
        return false;
    }
}
