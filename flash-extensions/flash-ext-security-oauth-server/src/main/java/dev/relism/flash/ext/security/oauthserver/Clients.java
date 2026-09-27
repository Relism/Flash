package dev.relism.flash.ext.security.oauthserver;

import com.nimbusds.jose.util.JSONObjectUtils;
import dev.relism.flash.ext.cache.Cache;
import dev.relism.flash.ext.security.PublicUrl;
import dev.relism.flash.ext.security.oauthserver.OAuthServer.OAuthError;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Finds a client — registered, or described by the metadata document its https {@code client_id} points
 * at — and validates what a client says about itself.
 */
final class Clients {

    static final List<String> AUTH_METHODS = List.of("none", "client_secret_basic", "client_secret_post");
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "[::1]", "localhost");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final OAuthStore store;
    private final boolean localDocuments;
    private final Cache<String, Optional<OAuthClient>> documents;

    /** @param documents bounded and expiring: the keys are URLs whoever calls the server chose */
    Clients(OAuthStore store, boolean localDocuments, Cache<String, Optional<OAuthClient>> documents) {
        this.store = store;
        this.localDocuments = localDocuments;
        this.documents = documents;
    }

    /** The client, or {@code null} when there is none by that id. */
    OAuthClient find(String id) {
        if (id == null) return null;
        if (!id.startsWith("https://") && !(localDocuments && id.startsWith("http://"))) return store.client(id);
        return documents.get(id, this::document).orElse(null);
    }

    /**
     * A client ID Metadata Document: fetched from where its id points, under {@link PublicUrl}'s rules,
     * and trusted only if it names that same URL. Such a client holds no secret, so it is public. A URL
     * that fails is remembered as failing, so naming it again does not fetch it again.
     */
    private Optional<OAuthClient> document(String url) {
        try {
            if (!localDocuments) PublicUrl.require(url);
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 16_384) return Optional.empty();
            Map<String, Object> metadata = new HashMap<>(JSONObjectUtils.parse(response.body()));
            if (!url.equals(metadata.remove("client_id"))) return Optional.empty();
            metadata.putIfAbsent("token_endpoint_auth_method", "none");
            if (!"none".equals(metadata.get("token_endpoint_auth_method"))) return Optional.empty();
            return Optional.of(new OAuthClient(url, null, validate(metadata, false)));
        } catch (Exception unusable) {
            return Optional.empty();
        }
    }

    /**
     * RFC 7591 metadata with its defaults filled in: {@code authorization_code}, {@code code}, and
     * {@code client_secret_basic}. Only an application registering a client itself may grant it
     * {@code client_credentials}: a client registering itself would otherwise mint its own tokens.
     *
     * @throws OAuthError naming the RFC 7591 error
     */
    static Map<String, Object> validate(Map<String, Object> given, boolean trusted) {
        Map<String, Object> metadata = new HashMap<>(given);
        metadata.putIfAbsent("grant_types", List.of("authorization_code"));
        metadata.putIfAbsent("response_types", List.of("code"));
        metadata.putIfAbsent("token_endpoint_auth_method", "client_secret_basic");
        List<?> grants = list(metadata.get("grant_types"));
        Set<String> allowed = trusted ? Set.of("authorization_code", "refresh_token", "client_credentials") : Set.of("authorization_code", "refresh_token");
        if (grants == null || grants.isEmpty() || !allowed.containsAll(grants)) throw new OAuthError("invalid_client_metadata", "Unsupported grant_types");
        if (!List.of("code").equals(metadata.get("response_types")) && grants.contains("authorization_code")) {
            throw new OAuthError("invalid_client_metadata", "response_types must be [\"code\"]");
        }
        if (!AUTH_METHODS.contains(metadata.get("token_endpoint_auth_method"))) throw new OAuthError("invalid_client_metadata", "Unsupported token_endpoint_auth_method");
        if (grants.contains("client_credentials") && "none".equals(metadata.get("token_endpoint_auth_method"))) {
            throw new OAuthError("invalid_client_metadata", "client_credentials needs a confidential client");
        }
        List<?> uris = list(metadata.getOrDefault("redirect_uris", List.of()));
        if (uris == null || grants.contains("authorization_code") && uris.isEmpty()) throw new OAuthError("invalid_redirect_uri", "redirect_uris is required");
        for (Object uri : uris) {
            if (!(uri instanceof String text) || !redirectable(text)) throw new OAuthError("invalid_redirect_uri", "Not an https or loopback URI: " + uri);
        }
        return metadata;
    }

    /** OAuth 2.1 §2.3.1: exactly as registered, except that a loopback redirect may use any port (RFC 8252 §7.3). */
    static boolean matches(String registered, String requested) {
        if (registered.equals(requested)) return true;
        try {
            URI a = URI.create(registered), b = URI.create(requested);
            return "http".equals(a.getScheme()) && "http".equals(b.getScheme()) && LOOPBACK.contains(a.getHost()) && a.getHost().equals(b.getHost())
                    && a.getRawPath().equals(b.getRawPath()) && Objects.equals(a.getRawQuery(), b.getRawQuery()) && b.getRawFragment() == null;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean redirectable(String uri) {
        try {
            URI parsed = URI.create(uri);
            return parsed.getRawFragment() == null && parsed.getHost() != null
                    && ("https".equals(parsed.getScheme()) || "http".equals(parsed.getScheme()) && LOOPBACK.contains(parsed.getHost()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : null;
    }
}
