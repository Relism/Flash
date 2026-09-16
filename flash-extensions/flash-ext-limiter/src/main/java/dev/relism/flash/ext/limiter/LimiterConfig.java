package dev.relism.flash.ext.limiter;

import dev.relism.flash.exceptions.InitializationException;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extension configuration: holds the named {@link KeyResolver} registry.
 *
 * <p>Resolvers are registered during the <em>config phase</em> (before {@code install}).
 * After {@link LimiterExtension#install} is called, the registry is consulted once per
 * route/handler at boot to capture the resolver lambda directly into the middleware closure.
 * There is no map lookup on the request hot-path.
 *
 * <p>The built-in {@code "ip"} resolver is always present and extracts the client IP from
 * {@code X-Forwarded-For} (first address) or {@code X-Real-IP}. Override it with
 * {@code registerResolver("ip", ...)} if needed.
 *
 * <pre>{@code
 * LimiterConfig conf = new LimiterConfig()
 *     .registerResolver("auth_user", req -> {
 *         // custom logic — e.g. key by the authenticated caller
 *         return SecurityIdentity.current() != null ? SecurityIdentity.current().principal().name() : "anonymous";
 *     });
 *
 * app.install(new LimiterExtension(conf));
 * }</pre>
 */
public final class LimiterConfig {

    private final Map<String, KeyResolver> resolvers = new LinkedHashMap<>();

    public LimiterConfig() {
        // Built-in mandatory "ip" resolver.
        // Resolution order (standard reverse-proxy chain):
        //   1. X-Forwarded-For — first address (client behind one or more proxies)
        //   2. X-Real-IP       — single forwarded IP (nginx proxy_set_header X-Real-IP)
        //   3. Socket address  — direct connection, no proxy headers (zero alloc: the
        //                        InetSocketAddress already exists from accept(); only
        //                        getHostAddress() allocates a String, and only when reached)
        resolvers.put("ip", req -> {
            String xff = req.header("X-Forwarded-For");
            if (xff != null) {
                int comma = xff.indexOf(',');
                return comma > 0 ? xff.substring(0, comma).strip() : xff.strip();
            }
            String xri = req.header("X-Real-IP");
            if (xri != null) return xri.strip();
            InetSocketAddress addr = req.remoteAddress();
            return addr != null ? addr.getAddress().getHostAddress() : "unknown";
        });
    }

    /**
     * Registers (or replaces) a named key resolver. Returns {@code this} for fluent chaining.
     *
     * @param name     identifier referenced by {@link Limit#key()} and {@link Guard#limit}
     * @param resolver lambda that extracts the partition key from a request
     */
    public LimiterConfig registerResolver(String name, KeyResolver resolver) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Resolver name must not be blank");
        if (resolver == null)               throw new IllegalArgumentException("Resolver must not be null");
        resolvers.put(name, resolver);
        return this;
    }

    /**
     * Returns the resolver for {@code name}.
     *
     * @throws InitializationException if no resolver with that name has been registered —
     *         checked at boot time so misconfigurations surface immediately.
     */
    KeyResolver requireResolver(String name) {
        KeyResolver r = resolvers.get(name);
        if (r == null) throw new InitializationException(
                "Rate-limit resolver \"" + name + "\" is not registered. " +
                "Call LimiterConfig.registerResolver(\"" + name + "\", req -> ...) before install.");
        return r;
    }
}
