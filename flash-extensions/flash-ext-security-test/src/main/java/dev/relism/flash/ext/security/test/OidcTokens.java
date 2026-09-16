package dev.relism.flash.ext.security.test;

import com.nimbusds.jose.util.JSONObjectUtils;
import dev.relism.flash.testing.FlashRequest;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Real tokens from a real provider — Keycloak in Testcontainers, typically. */
public final class OidcTokens {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private OidcTokens() {}

    /**
     * Sends the request with an access token obtained by the resource owner password grant. The
     * client must allow direct access grants — a test realm's setting, never a production one.
     */
    public static Consumer<FlashRequest> passwordGrant(String issuer, String clientId, String clientSecret, String username, String password) {
        try {
            String discovery = issuer + (issuer.endsWith("/") ? "" : "/") + ".well-known/openid-configuration";
            String tokenEndpoint = (String) JSONObjectUtils.parse(get(HttpRequest.newBuilder(URI.create(discovery)))).get("token_endpoint");
            String form = "grant_type=password&scope=openid&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
                    + "&username=" + encode(username) + "&password=" + encode(password);
            String header = "Bearer " + JSONObjectUtils.parse(get(HttpRequest.newBuilder(URI.create(tokenEndpoint))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)))).get("access_token");
            return request -> request.header("Authorization", header);
        } catch (Exception e) {
            throw new IllegalStateException("Could not obtain a token from " + issuer, e);
        }
    }

    private static String get(HttpRequest.Builder request) throws Exception {
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
