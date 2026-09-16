package dev.relism.flash.ext.security.test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.relism.flash.testing.FlashRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.net.URLEncoder;
import java.util.Set;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * An OpenID Provider on a random local port: discovery, keys, and a token endpoint for the
 * authorization code and refresh flows. Sign-in is approved at once, as {@link #signInAs}.
 */
public final class FakeOidcProvider implements AutoCloseable {

    private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    private final RSAKey key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    private final Map<String, String> nonces = new ConcurrentHashMap<>();
    private final String issuer = "http://127.0.0.1:" + server.getAddress().getPort();
    private volatile String subject = "alice";
    private volatile Map<String, Object> claims = Map.of();
    private volatile long expiresIn = 300;
    /** Null accepts any client, which is what every test that is not about client registration wants. */
    private volatile String clientSecret;
    private volatile Set<String> redirectUris = Set.of();

    public FakeOidcProvider() throws Exception {
        server.createContext("/.well-known/openid-configuration", ex -> send(ex, 200, JSONObjectUtils.toJSONString(Map.of(
                "issuer", issuer, "authorization_endpoint", issuer + "/authorize", "token_endpoint", issuer + "/token",
                "jwks_uri", issuer + "/jwks", "end_session_endpoint", issuer + "/logout"))));
        server.createContext("/jwks", ex -> send(ex, 200, new JWKSet(key.toPublicJWK()).toString()));
        server.createContext("/authorize", ex -> {
            Map<String, String> query = form(ex.getRequestURI().getRawQuery());
            if (!redirectUris.isEmpty() && !redirectUris.contains(query.get("redirect_uri"))) {
                send(ex, 400, "Unregistered redirect_uri");
                return;
            }
            String code = UUID.randomUUID().toString();
            nonces.put(code, query.get("nonce"));
            ex.getResponseHeaders().add("Location", query.get("redirect_uri") + "?code=" + code + "&state=" + query.get("state"));
            send(ex, 302, "");
        });
        server.createContext("/token", ex -> {
            String basic = ex.getRequestHeaders().getFirst("Authorization");
            if (clientSecret != null && (basic == null || !new String(Base64.getDecoder().decode(basic.substring(6)), StandardCharsets.UTF_8)
                    .endsWith(":" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)))) {
                send(ex, 401, "{\"error\":\"invalid_client\"}");
                return;
            }
            Map<String, String> body = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String nonce = nonces.remove(body.getOrDefault("code", ""));
            if (body.get("grant_type").equals("authorization_code") && nonce == null) {
                send(ex, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            Map<String, Object> id = new HashMap<>(claims);
            id.put("aud", "app");
            if (nonce != null) id.put("nonce", nonce);
            send(ex, 200, JSONObjectUtils.toJSONString(Map.of("access_token", token(subject, claims), "id_token", token(subject, id),
                    "refresh_token", UUID.randomUUID().toString(), "expires_in", expiresIn, "scope", "openid email")));
        });
        server.start();
    }

    /** Refuses, from now on, any other secret or redirect URI — as a provider with this client registered would. */
    public FakeOidcProvider client(String secret, String... redirectUris) {
        this.clientSecret = secret;
        this.redirectUris = Set.of(redirectUris);
        return this;
    }

    public String issuer() {
        return issuer;
    }

    /** Who sign-in and refresh vouch for, with what extra claims; the client id is always {@code app}. */
    public FakeOidcProvider signInAs(String subject, Map<String, Object> claims) {
        this.subject = subject;
        this.claims = claims;
        return this;
    }

    /** Lifetime of the tokens sign-in and refresh issue — {@code 0} expires a session at once. */
    public FakeOidcProvider expiresIn(long seconds) {
        this.expiresIn = seconds;
        return this;
    }

    /** A signed token for {@code subject}, valid five minutes, with {@code claims} added. */
    public String token(String subject, Map<String, Object> claims) {
        try {
            JWTClaimsSet.Builder set = new JWTClaimsSet.Builder().issuer(issuer).subject(subject)
                    .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 300_000));
            claims.forEach(set::claim);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), set.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sends the request with a bearer {@link #token}. */
    public Consumer<FlashRequest> bearer(String subject, Map<String, Object> claims) {
        String header = "Bearer " + token(subject, claims);
        return request -> request.header("Authorization", header);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> fields = new HashMap<>();
        if (encoded != null) for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) fields.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return fields;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) try (var out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
    }
}
