package dev.relism.flash.ext.security.apikey;

import dev.relism.flash.ext.security.AuthenticationFailedException;
import dev.relism.flash.ext.security.AuthenticationMechanism;
import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.ext.security.SecurityExtension;
import dev.relism.flash.ext.security.SecurityScheme;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.models.Request;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * API keys sent as {@code Authorization: Bearer <prefix>_<id>.<secret>}. The prefix makes a key
 * recognisable at a glance and to secret scanners; the id is what the store is queried by; only a
 * SHA-256 of the secret is ever stored — a KDF would add nothing to 192 random bits.
 *
 * <pre>{@code
 * app.install(new SecurityExtension())
 *    .install(new ApiKeyExtension<>("gk", keys::find));
 * }</pre>
 */
public final class ApiKeyExtension<G> implements FlashExtension, AuthenticationMechanism {

    private static final AuthenticationFailedException INVALID = new AuthenticationFailedException("Bearer error=\"invalid_token\"");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final String prefix;
    private final String bearer;
    private final ApiKeyStore<G> store;

    /** @param prefix identifies this application's keys; letters and digits only */
    public ApiKeyExtension(String prefix, ApiKeyStore<G> store) {
        if (!prefix.matches("[A-Za-z0-9]+")) throw new IllegalArgumentException("API key prefix must be alphanumeric: " + prefix);
        this.prefix = prefix;
        this.bearer = "Bearer " + prefix + "_";
        this.store = store;
    }

    /** A new key: 72 bits of id, 192 bits of secret. */
    public GeneratedApiKey generate() {
        String id = random(9);
        String secret = random(24);
        return new GeneratedApiKey(id, prefix + "_" + id + "." + secret, hash(secret));
    }

    @Override
    public Principal authenticate(Request req) {
        String header = req.header("Authorization");
        if (header == null || !header.startsWith(bearer)) return null;
        int dot = header.indexOf('.', bearer.length());
        if (dot < 0) throw INVALID;
        ApiKey<G> key = store.find(header.substring(bearer.length(), dot));
        if (key == null || !matches(header.substring(dot + 1), key.secretHash()) || !key.isActive()) throw INVALID;
        return new ApiKeyPrincipal<>(key.id(), key.grant());
    }

    @Override
    public List<SecurityScheme> schemes() {
        return List.of(SecurityScheme.bearer("apiKey", prefix + "_<id>.<secret>"));
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        ctx.provide(ApiKeyExtension.class, this);
        ctx.onReady(() -> ctx.require(SecurityExtension.class).mechanism(this));
    }

    private static boolean matches(String secret, String secretHash) {
        return MessageDigest.isEqual(digest(secret), Base64.getUrlDecoder().decode(secretHash));
    }

    private static String hash(String secret) {
        return BASE64URL.encodeToString(digest(secret));
    }

    private static byte[] digest(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return BASE64URL.encodeToString(value);
    }
}
