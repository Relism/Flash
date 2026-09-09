package dev.relism.flash.ext.cache.caffeine;

import dev.relism.flash.ext.cache.CacheManager;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;

/**
 * Installs an in-process {@link CacheManager} backed by Caffeine.
 *
 * <pre>{@code
 * FlashApp.create(8080)
 *     .install(new CaffeineCacheExtension())
 *     .scan("dev.example.api");
 * }</pre>
 *
 * <p>No configuration. Each cache declares its own size and TTL where it is built, because those
 * are properties of what is being cached, not of the process caching it.
 *
 * <p>Caches are dropped through {@link FlashContext#onClose}, so a stopped app does not keep its
 * values alive — which matters when many apps start and stop in one JVM, as they do under test.
 */
public final class CaffeineCacheExtension implements FlashExtension {

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        ctx.supply(CacheManager.class, services -> {
            CaffeineCacheManager manager = new CaffeineCacheManager();
            services.onClose(manager::clear);
            return manager;
        });
    }
}
