package dev.relism.ext.openapi;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mutable registry of {@link OpenApiSecurityContributor}s.
 *
 * <p>Created and provided to the {@link dev.relism.extension.ExtensionContext} by
 * {@link OpenApiExtension} at install time. Other extensions (e.g. {@code flash-ext-oidc})
 * retrieve it via {@code ctx.find(OpenApiSecurityRegistry.class)} and register their
 * contributor — the OpenAPI extension then picks it up lazily at spec-generation time.
 *
 * <p>Thread-safe: {@link CopyOnWriteArrayList} allows concurrent reads during spec
 * generation without blocking registration.
 */
public final class OpenApiSecurityRegistry {

    private final List<OpenApiSecurityContributor> contributors = new CopyOnWriteArrayList<>();

    /** Registers a contributor. Safe to call concurrently. */
    public void add(OpenApiSecurityContributor contributor) {
        contributors.add(contributor);
    }

    /** Returns an unmodifiable snapshot of all registered contributors. */
    public List<OpenApiSecurityContributor> contributors() {
        return Collections.unmodifiableList(contributors);
    }
}
