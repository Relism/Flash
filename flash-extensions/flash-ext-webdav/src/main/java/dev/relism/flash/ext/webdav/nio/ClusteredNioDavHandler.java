package dev.relism.flash.ext.webdav.nio;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A directory several processes serve, on one host or several, with the directory on a file system
 * they all mount. The bytes in use, uploads in progress and locks live in {@code .dav} under the
 * root, which every process reads and writes under a file lock, so all of them count the same quota
 * and honor the same locks. Clients do not reach {@code .dav}, unless {@link #stateVisible} shows it.
 *
 * <pre>{@code
 * @Dav("/drive/**") @Authenticated
 * final class Drive extends ClusteredNioDavHandler {
 *     @Override protected Path root(Request req) { return Path.of("/mnt/drive", me().id()); }
 *     @Override protected long quota(Request req) { return me().quotaBytes(); }
 * }
 * }</pre>
 *
 * <p>The file system must offer byte-range locks every process sees, and atomic rename.
 */
public abstract non-sealed class ClusteredNioDavHandler extends NioDavHandler {

    /**
     * Whether clients see {@code .dav} under the root: as an ordinary collection, which they can
     * read and change, the state included. Hidden unless overridden.
     */
    protected boolean stateVisible() {
        return false;
    }

    @Override
    final NioStorage storage(Path root) throws IOException {
        return NioStorage.of(root, true, !stateVisible());
    }
}
