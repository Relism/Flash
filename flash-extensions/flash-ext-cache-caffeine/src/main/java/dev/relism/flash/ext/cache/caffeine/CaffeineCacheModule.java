package dev.relism.flash.ext.cache.caffeine;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.cache.CacheManager;

/**
 * An in-process {@link CacheManager} backed by Caffeine.
 *
 * <pre>{@code
 * app.install(new CaffeineCacheModule());
 * }</pre>
 *
 * <p>No configuration. Each cache declares its own size and TTL where it is built, because those
 * are properties of what is being cached, not of the process caching it. The manager is closed
 * with the app, which drops every cache, so a stopped app does not keep its values alive — which
 * matters when many apps start and stop in one JVM, as they do under test.
 */
public final class CaffeineCacheModule implements Module {

    @Override
    public void install(App app) {
        app.add(CacheManager.class, CaffeineCacheManager.class);
    }
}
