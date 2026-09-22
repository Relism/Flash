package dev.relism.flash.ext.security.oauthserver;

/** Where the authorization server keeps its clients, grants and consents. {@link InMemoryOAuthStore} unless one that survives a restart is configured. */
public interface OAuthStore {

    /** The client registered under {@code id}, or {@code null}. */
    OAuthClient client(String id);

    void save(OAuthClient client);

    void save(OAuthGrant grant);

    /** The grant stored under {@code hash}, or {@code null}. */
    OAuthGrant find(String hash);

    /**
     * Marks the grant used and returns it as it was before: a non-null {@link OAuthGrant#usedAt()} means it
     * had been used already. {@code null} for an unknown hash. Atomic — two concurrent uses must not both
     * see it unused.
     */
    OAuthGrant use(String hash);

    /** Every grant of {@code family} stops working. */
    void revoke(String family);

    /** The scope {@code subject} granted {@code clientId} at its last consent, or {@code null} for none. */
    String consent(String subject, String clientId);

    void consent(String subject, String clientId, String scope);
}
