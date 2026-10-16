package dev.relism.flash.ext.webdav.nio;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A directory one process serves: the bytes in use, uploads in progress and locks live in its
 * memory. Every name under the root, {@code .dav} included, is the client's.
 *
 * <pre>{@code
 * @Dav("/drive/**") @Authenticated
 * final class Drive extends StandaloneNioDavHandler {
 *     @Override protected Path root(Request req) { return disk.resolve(me().id()); }
 *     @Override protected long quota(Request req) { return quotas.forRole(me().role()); }
 * }
 * }</pre>
 *
 * <p>When several processes serve the same directory, use {@link ClusteredNioDavHandler}: with this one
 * each process would count the quota and keep the locks alone.
 */
public abstract non-sealed class StandaloneNioDavHandler extends NioDavHandler {

    @Override
    final NioStorage storage(Path root) throws IOException {
        return NioStorage.of(root, false, false);
    }
}
