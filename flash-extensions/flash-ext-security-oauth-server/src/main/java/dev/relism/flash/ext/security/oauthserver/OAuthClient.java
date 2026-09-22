package dev.relism.flash.ext.security.oauthserver;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A client of the authorization server, described by its RFC 7591 metadata exactly as it was registered
 * (defaults filled in). A confidential client's secret is kept only as a hash; a public one has none.
 *
 * @param id either issued at registration, or the https URL of the client's metadata document
 */
public record OAuthClient(String id, String secretHash, Map<String, Object> metadata) {

    public OAuthClient {
        metadata = Map.copyOf(metadata);
    }

    /** {@code none}, {@code client_secret_basic} or {@code client_secret_post}. */
    public String authMethod() {
        return (String) metadata.get("token_endpoint_auth_method");
    }

    @SuppressWarnings("unchecked")
    public List<String> redirectUris() {
        return (List<String>) metadata.getOrDefault("redirect_uris", List.of());
    }

    @SuppressWarnings("unchecked")
    public Set<String> grantTypes() {
        return Set.copyOf((List<String>) metadata.get("grant_types"));
    }

    /** What a consent page calls it: its {@code client_name}, or its id when it gave none. */
    public String name() {
        return (String) metadata.getOrDefault("client_name", id);
    }
}
