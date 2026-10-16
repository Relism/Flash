package dev.relism.flash.ext.webdav.nio;

import dev.relism.flash.ext.webdav.DavHandler;
import dev.relism.flash.ext.webdav.DavStorage;
import dev.relism.flash.http.Request;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A WebDAV mount over a directory of any {@code java.nio.file} file system: the local disk, a zip
 * file, an in-memory one. {@link #root} says which directory a request sees; it is created when
 * missing.
 *
 * <p>Extend one of its two kinds, which say where the state of a directory lives (the bytes in use,
 * uploads in progress, locks):
 * <ul>
 *   <li>{@link StandaloneNioDavHandler}: one process serves the directory, and keeps its state in memory;</li>
 *   <li>{@link ClusteredNioDavHandler}: several processes serve it, and keep its state in {@code .dav}
 *       under the root, which clients do not reach unless the handler shows it.</li>
 * </ul>
 *
 * <p>Dead properties are kept in the files' extended attributes (xattrs on Linux and macOS, alternate
 * data streams on Windows); where the file system has none, in memory.
 */
public abstract sealed class NioDavHandler extends DavHandler permits StandaloneNioDavHandler, ClusteredNioDavHandler {

    // ponytail: one entry per root ever served, never dropped; bounded by the spaces an app has
    private final Map<Path, NioStorage> storages = new ConcurrentHashMap<>();

    NioDavHandler() {}

    /** The directory this request sees. */
    protected abstract Path root(Request req) throws IOException;

    /** The storage over {@code root}, made the first time a request reaches it. */
    abstract NioStorage storage(Path root) throws IOException;

    @Override
    protected final DavStorage open(Request req) throws IOException {
        Path root = root(req).toAbsolutePath().normalize();
        NioStorage storage = storages.get(root);
        if (storage == null) storages.put(root, storage = storage(root));
        return storage;
    }
}
