package dev.relism.flash.ext.mcp;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal, self-contained fake OIDC provider for tests: real discovery document, real JWKS
 * endpoint, real RS256-signed tokens — no network dependency beyond localhost, no mocking
 * framework. Exercises {@code flash-ext-oidc}'s actual discovery + JWKS + JWT validation path.
 */
final class FakeOidcProvider implements AutoCloseable {

    private final HttpServer server;
    private final String issuer;
    private final RSAKey rsaKey;

    FakeOidcProvider() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        this.rsaKey = new RSAKey.Builder((RSAPublicKey) kp.getPublic())
                .privateKey((RSAPrivateKey) kp.getPrivate())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID(UUID.randomUUID().toString())
                .build();

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.issuer = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/.well-known/openid-configuration", ex -> respond(ex, discoveryDocument()));
        server.createContext("/jwks", ex -> respond(ex, new JWKSet(rsaKey.toPublicJWK()).toJSONObject().toString()));
        server.setExecutor(null);
        server.start();
    }

    String issuer() { return issuer; }

    /** Mints a valid RS256 access token — bearer-validation only, no full authorization-code round-trip needed. */
    String signToken(String subject, String audience) {
        return signToken(subject, audience, null, NO_ROLES);
    }

    /**
     * Same as {@link #signToken(String, String)}, plus a {@code scope} claim (space-delimited,
     * matching {@link dev.relism.flash.ext.auth.Claims#hasScope}'s default claim path) and a
     * Keycloak-shaped {@code realm_access.roles} claim (matching {@code McpConfig}'s default
     * {@code rolesClaimPath}) when {@code roles} is non-empty.
     */
    String signToken(String subject, String audience, String scope, String... roles) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .audience(audience)
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)));
            if (scope != null) builder.claim("scope", scope);
            if (roles.length > 0) builder.claim("realm_access", Map.of("roles", List.of(roles)));
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), builder.build());
            jwt.sign(new RSASSASigner(rsaKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String[] NO_ROLES = new String[0];

    private String discoveryDocument() {
        return "{"
                + "\"issuer\":\"" + issuer + "\","
                + "\"authorization_endpoint\":\"" + issuer + "/auth\","
                + "\"token_endpoint\":\"" + issuer + "/token\","
                + "\"jwks_uri\":\"" + issuer + "/jwks\""
                + "}";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    @Override
    public void close() { server.stop(0); }
}
