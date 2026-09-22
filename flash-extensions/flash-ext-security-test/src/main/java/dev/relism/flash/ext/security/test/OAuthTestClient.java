package dev.relism.flash.ext.security.test;

import com.nimbusds.jose.util.JSONObjectUtils;
import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.testing.FlashRequest;
import dev.relism.flash.testing.FlashResponse;
import dev.relism.flash.testing.FlashTest;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * An OAuth 2.1 client of the application under test, driving its own authorization server the way an MCP
 * client does: discovery (RFC 8414), registration (RFC 7591), authorization code with PKCE, consent, and
 * the token exchange. Its user signs in as any principal, through {@link TestSecurity}.
 *
 * <pre>{@code
 * OAuthTestClient.Tokens tokens = OAuthTestClient.register(app).authorize(() -> "alice");
 * app.request().with(tokens.bearer()).post("/mcp");
 * }</pre>
 */
public final class OAuthTestClient {

    /** Where the authorization server sends the code: never contacted, only read off the redirect. */
    public static final String REDIRECT_URI = "http://127.0.0.1/callback";

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FlashTest app;
    private final Map<String, Object> metadata;
    private final String clientId;

    /** What the exchange returned. */
    public record Tokens(String accessToken, String refreshToken, String scope) {

        public Consumer<FlashRequest> bearer() {
            return request -> request.header("Authorization", "Bearer " + accessToken);
        }
    }

    private OAuthTestClient(FlashTest app, Map<String, Object> metadata, String clientId) {
        this.app = app;
        this.metadata = metadata;
        this.clientId = clientId;
    }

    /** A public client, registered at the server's registration endpoint, allowed refresh tokens. */
    public static OAuthTestClient register(FlashTest app) {
        Map<String, Object> metadata = json(app.get("/.well-known/oauth-authorization-server").expectStatus(200));
        Map<String, Object> client = json(app.request().header("Content-Type", "application/json")
                .body(JSONObjectUtils.toJSONString(Map.of("client_name", "Test client", "redirect_uris", List.of(REDIRECT_URI),
                        "grant_types", List.of("authorization_code", "refresh_token"), "token_endpoint_auth_method", "none")))
                .post(path(metadata, "registration_endpoint")).expectStatus(201));
        return new OAuthTestClient(app, metadata, (String) client.get("client_id"));
    }

    public String clientId() {
        return clientId;
    }

    /** Signs in as {@code user}, allows this client if asked to, and exchanges the code: the application's default resource and no scope. */
    public Tokens authorize(Principal user) {
        return authorize(user, Map.of());
    }

    /** The same, with extra authorization request parameters — {@code scope}, {@code resource}. */
    public Tokens authorize(Principal user, Map<String, String> parameters) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String verifier = BASE64URL.encodeToString(secret);
        StringBuilder query = new StringBuilder("response_type=code&client_id=" + encode(clientId) + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&state=test&code_challenge=" + s256(verifier) + "&code_challenge_method=S256");
        parameters.forEach((key, value) -> query.append('&').append(key).append('=').append(encode(value)));

        String authorize = path(metadata, "authorization_endpoint");
        String location = app.request().with(TestSecurity.as(user)).get(authorize + "?" + query).expectStatus(302).header("Location");
        if (!location.startsWith(REDIRECT_URI)) {
            location = app.request().with(TestSecurity.as(user)).header("Content-Type", "application/x-www-form-urlencoded")
                    .body(query + "&consent=allow").post(authorize).expectStatus(303).header("Location");
        }
        String code = parameter(location, "code");
        if (code == null) throw new AssertionError("No code: " + location);
        return tokens(json(app.request().header("Content-Type", "application/x-www-form-urlencoded")
                .body("grant_type=authorization_code&client_id=" + encode(clientId) + "&code=" + encode(code)
                        + "&redirect_uri=" + encode(REDIRECT_URI) + "&code_verifier=" + verifier)
                .post(path(metadata, "token_endpoint")).expectStatus(200)));
    }

    /** Trades a refresh token for a new pair. */
    public Tokens refresh(String refreshToken) {
        return tokens(json(app.request().header("Content-Type", "application/x-www-form-urlencoded")
                .body("grant_type=refresh_token&client_id=" + encode(clientId) + "&refresh_token=" + encode(refreshToken))
                .post(path(metadata, "token_endpoint")).expectStatus(200)));
    }

    private static Tokens tokens(Map<String, Object> response) {
        return new Tokens((String) response.get("access_token"), (String) response.get("refresh_token"), (String) response.get("scope"));
    }

    private static String path(Map<String, Object> metadata, String endpoint) {
        return URI.create((String) metadata.get(endpoint)).getRawPath();
    }

    private static String parameter(String uri, String name) {
        for (String pair : URI.create(uri).getRawQuery().split("&")) {
            if (pair.startsWith(name + "=")) return java.net.URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static Map<String, Object> json(FlashResponse response) {
        try {
            return JSONObjectUtils.parse(response.body());
        } catch (java.text.ParseException e) {
            throw new AssertionError("Not JSON: " + response.body(), e);
        }
    }

    private static String s256(String verifier) {
        try {
            return BASE64URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
