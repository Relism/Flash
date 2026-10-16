package dev.relism.flash.ext.webdav;

import dev.relism.flash.App;
import dev.relism.flash.ext.webdav.internal.Space;
import dev.relism.flash.Module;
import dev.relism.flash.http.Exchange;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.routing.Next;
import dev.relism.flash.routing.Route;
import dev.relism.flash.routing.RouteHook;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.namespace.QName;

/**
 * WebDAV for an app: what its {@link Dav} mounts share in memory (the space of each storage, dead
 * properties), the check of their routes at boot, and the {@code OPTIONS} answer Windows needs above a
 * mount.
 *
 * <pre>{@code
 * App.create()
 *     .install(new SecurityModule(), new BasicAuth(accounts::find), new DavModule())
 *     .scan("com.acme");
 * }</pre>
 *
 * <p>Windows asks {@code OPTIONS /} of the server before it uses a share at any path, and gives up on a
 * {@code 404} or {@code 401}. So {@code OPTIONS} on the root and on every path above a mount is
 * answered with {@code DAV: 1, 2} and nothing else, before any authentication; an app's own
 * {@code OPTIONS} route on such a path wins, and a CORS preflight passes through. Install this
 * module before any {@code use} that authenticates every request.
 */
public final class DavModule implements Module, RouteHook {

    // ponytail: one entry per storage ever served, never dropped; bounded by the spaces an app has
    private final Map<DavStorage, Space> spaces = new ConcurrentHashMap<>();
    final Map<DavStorage, Map<DavPath, Map<QName, String>>> memory = new ConcurrentHashMap<>();
    private long maxLockSeconds = Duration.ofHours(1).toSeconds();
    private final Set<String> above = new HashSet<>();
    private final Set<String> ownOptions = new HashSet<>();

    /** The longest lock a client is granted, one hour unless set; at least 600 seconds, the lock Finder asks for. */
    public DavModule maxLockTimeout(Duration timeout) {
        if (timeout.toSeconds() < 600)
            throw new IllegalArgumentException("maxLockTimeout must be at least 600 seconds: Finder locks for 10 minutes");
        this.maxLockSeconds = timeout.toSeconds();
        return this;
    }

    long maxLockSeconds() {
        return maxLockSeconds;
    }

    /** The space of a storage in this process's memory, by the storage's {@code equals}. */
    Space space(DavStorage storage) {
        return spaces.computeIfAbsent(storage, Space::new);
    }

    @Override
    public void install(App app) {
        app.add(DavModule.class, this).add(RouteHook.class, this).use(this::answerAbove);
    }

    /** Checks each {@link DavHandler} route, and notes the paths above its mount. */
    @Override
    public void routed(Route route) {
        Class<?> handler = route.handler();
        if (handler == null || !DavHandler.class.isAssignableFrom(handler)) {
            if (route.method() == HttpMethod.OPTIONS) ownOptions.add(route.path());
            return;
        }
        String name = handler.getSimpleName();
        if (route.method() != null)
            throw new IllegalStateException(name + " is a DavHandler: route it for every method, with @Dav or app.any");
        if (!route.path().endsWith("/**"))
            throw new IllegalStateException(name + " at " + route.path() + ": a WebDAV mount covers a subtree; route it at "
                    + (route.path().equals("/") ? "" : route.path()) + "/**");
        String mount = route.path().substring(0, route.path().length() - 3);
        for (int slash = mount.indexOf('/'); slash >= 0; slash = mount.indexOf('/', slash + 1))
            above.add(slash == 0 ? "/" : mount.substring(0, slash));
    }

    private void answerAbove(Exchange exchange, Next next) throws Exception {
        Request request = exchange.request();
        if (request.method() != HttpMethod.OPTIONS || request.headerView("Access-Control-Request-Method") != null) {
            next.handle(exchange);
            return;
        }
        String path = request.path();
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (!above.contains(path) || ownOptions.contains(path)) {
            next.handle(exchange);
            return;
        }
        exchange.response().status(200).header("DAV", "1, 2").header("MS-Author-Via", "DAV").header("Allow", "OPTIONS");
    }
}
