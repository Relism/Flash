package dev.relism.flash.ext.security.oauthserver;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One instance's clients, grants and consents, lost on restart. Expired grants are swept whenever one is saved. */
public final class InMemoryOAuthStore implements OAuthStore {

    private final Map<String, OAuthClient> clients = new ConcurrentHashMap<>();
    private final Map<String, OAuthGrant> grants = new ConcurrentHashMap<>();
    private final Map<String, String> consents = new ConcurrentHashMap<>();

    @Override
    public OAuthClient client(String id) {
        return clients.get(id);
    }

    @Override
    public void save(OAuthClient client) {
        clients.put(client.id(), client);
    }

    @Override
    public void save(OAuthGrant grant) {
        Instant now = Instant.now();
        grants.values().removeIf(g -> g.expiresAt().isBefore(now));
        grants.put(grant.hash(), grant);
    }

    @Override
    public OAuthGrant find(String hash) {
        return grants.get(hash);
    }

    @Override
    public OAuthGrant use(String hash) {
        OAuthGrant[] before = new OAuthGrant[1];
        grants.computeIfPresent(hash, (key, grant) -> {
            before[0] = grant;
            return grant.usedAt() == null ? grant.used(Instant.now()) : grant;
        });
        return before[0];
    }

    @Override
    public void revoke(String family) {
        grants.values().removeIf(grant -> grant.family().equals(family));
    }

    @Override
    public String consent(String subject, String clientId) {
        return consents.get(subject + " " + clientId);
    }

    @Override
    public void consent(String subject, String clientId, String scope) {
        consents.put(subject + " " + clientId, scope);
    }
}
