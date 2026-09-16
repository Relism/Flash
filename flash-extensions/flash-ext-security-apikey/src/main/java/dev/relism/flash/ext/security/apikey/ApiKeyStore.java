package dev.relism.flash.ext.security.apikey;

/** Where the application keeps its API keys. */
@FunctionalInterface
public interface ApiKeyStore<G> {

    /** The key with this id, or {@code null}. */
    ApiKey<G> find(String id);
}
