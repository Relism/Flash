package dev.relism.flash.ext.oidc;

import net.minidev.json.JSONValue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP client for OAuth2 token endpoint operations (pure HTTP, no SDK).
 *
 * <p>Supports two client authentication methods (RFC 6749 §2.3):
 * <ul>
 *   <li>{@link ClientAuthMethod#POST} — credentials in form body ({@code client_secret_post})</li>
 *   <li>{@link ClientAuthMethod#BASIC} — credentials in {@code Authorization: Basic} header
 *       ({@code client_secret_basic})</li>
 * </ul>
 */
final class TokenClient {

    private final HttpClient       http;
    private final String           clientId;
    private final String           clientSecret;
    private final ClientAuthMethod authMethod;

    TokenClient(HttpClient http, OidcConfig config) {
        this.http         = http;
        this.clientId     = config.clientId();
        this.clientSecret = config.clientSecret();
        this.authMethod   = config.clientAuthMethod();
    }

    /** Authorization Code + PKCE exchange. */
    OidcTokenResponse exchangeCode(String tokenEndpoint,
                                   String code, String redirectUri,
                                   String codeVerifier) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type",    "authorization_code");
        params.put("code",          code);
        params.put("redirect_uri",  redirectUri);
        params.put("code_verifier", codeVerifier);
        return post(tokenEndpoint, params);
    }

    /** Refresh token grant. */
    OidcTokenResponse refresh(String tokenEndpoint, String refreshToken) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type",    "refresh_token");
        params.put("refresh_token", refreshToken);
        return post(tokenEndpoint, params);
    }

    // -- Internals ------------------------------------------------------------

    private OidcTokenResponse post(String url, Map<String, String> params) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded");

        if (authMethod == ClientAuthMethod.BASIC) {
            String creds = Base64.getEncoder().encodeToString(
                    (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            req.header("Authorization", "Basic " + creds);
        } else {
            params.put("client_id",     clientId);
            params.put("client_secret", clientSecret);
        }

        HttpResponse<String> resp = http.send(
                req.POST(HttpRequest.BodyPublishers.ofString(form(params))).build(),
                HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
            throw new IllegalStateException(
                    "Token endpoint [" + resp.statusCode() + "]: " + resp.body());

        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) JSONValue.parse(resp.body());

        return new OidcTokenResponse(
                (String)  json.get("access_token"),
                (String)  json.get("id_token"),
                (String)  json.get("refresh_token"),
                numInt(json, "expires_in",         300),
                numInt(json, "refresh_expires_in", 1800)
        );
    }

    private static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(enc(k)).append('=').append(enc(v));
        });
        return sb.toString();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static int numInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }
}
