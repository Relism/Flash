package dev.relism.ext.oidc;

import net.minidev.json.JSONValue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Fetches and parses the OIDC provider discovery document at
 * {@code {issuer}/.well-known/openid-configuration}.
 */
final class DiscoveryClient {

    private DiscoveryClient() {}

    static OidcProviderMetadata fetch(String issuer, HttpClient http) throws Exception {
        String url = issuer.endsWith("/")
                ? issuer + ".well-known/openid-configuration"
                : issuer + "/.well-known/openid-configuration";

        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200)
            throw new IllegalStateException(
                    "OIDC discovery failed [" + resp.statusCode() + "]: " + url);

        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) JSONValue.parse(resp.body());

        return new OidcProviderMetadata(
                require(doc, "authorization_endpoint"),
                require(doc, "token_endpoint"),
                (String) doc.get("userinfo_endpoint"),      // optional
                require(doc, "jwks_uri"),
                (String) doc.get("end_session_endpoint")    // optional
        );
    }

    private static String require(Map<String, Object> doc, String key) {
        Object v = doc.get(key);
        if (v == null) throw new IllegalStateException(
                "Discovery doc missing required field: " + key);
        return v.toString();
    }
}
