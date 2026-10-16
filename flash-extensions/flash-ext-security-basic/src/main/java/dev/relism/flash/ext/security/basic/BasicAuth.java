package dev.relism.flash.ext.security.basic;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.security.AuthenticationFailedException;
import dev.relism.flash.ext.security.AuthenticationMechanism;
import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.ext.security.SecurityScheme;
import dev.relism.flash.ext.security.form.PasswordEncoder;
import dev.relism.flash.ext.security.form.PasswordStore;
import dev.relism.flash.http.Request;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP Basic (RFC 7617): a user name and password with every request, checked against the same
 * {@link PasswordStore} and {@link PasswordEncoder} as form sign-in. Meant for clients that cannot
 * sign in any other way, such as an operating system mounting a WebDAV share; use it over TLS only.
 *
 * <pre>{@code
 * app.install(new SecurityModule(), new BasicAuth(accounts::byUsername));
 * }</pre>
 *
 * <p>A password hash costs a lot on purpose, and a Basic client sends the password every time, so a
 * verified pair is remembered for {@link #cacheFor} (5 minutes) by its SHA-256, up to
 * {@link #cacheSize} (10,000) pairs. A wrong password is never remembered; a changed one takes
 * effect when its entry expires.
 */
public final class BasicAuth implements Module, AuthenticationMechanism {

    private final PasswordStore store;
    private PasswordEncoder encoder = PasswordEncoder.pbkdf2();
    private String realm = "Flash";
    private long cacheNanos = Duration.ofMinutes(5).toNanos();
    private int cacheSize = 10_000;
    private String challenge;
    /** Checked against when no account matches, so an unknown name costs what a wrong password does. */
    private String unknownAccountHash;
    private final Map<ByteBuffer, Verified> verified = new LinkedHashMap<>(16, 0.75f, true);

    private record Verified(Principal principal, long expiresAt) {}

    public BasicAuth(PasswordStore store) {
        this.store = store;
    }

    public BasicAuth passwordEncoder(PasswordEncoder encoder) {
        this.encoder = encoder;
        return this;
    }

    /** The realm the {@code WWW-Authenticate} challenge names: what a client shows when it asks for a password. */
    public BasicAuth realm(String realm) {
        this.realm = realm;
        return this;
    }

    public BasicAuth cacheFor(Duration duration) {
        this.cacheNanos = duration.toNanos();
        return this;
    }

    /** {@code 0} turns the cache off. */
    public BasicAuth cacheSize(int entries) {
        this.cacheSize = entries;
        return this;
    }

    @Override
    public void install(App app) {
        challenge = schemes().getFirst().challenge();
        unknownAccountHash = encoder.encode("unknown-account");
        app.add(AuthenticationMechanism.class, this);
    }

    @Override
    public Principal authenticate(Request req) {
        String header = req.header("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        String credentials;
        try {
            credentials = new String(Base64.getDecoder().decode(header.substring(6).strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            throw new AuthenticationFailedException(challenge);
        }
        int colon = credentials.indexOf(':');
        if (colon < 0) throw new AuthenticationFailedException(challenge);
        ByteBuffer key = ByteBuffer.wrap(sha256(credentials));
        long now = System.nanoTime();
        synchronized (verified) {
            Verified known = verified.get(key);
            if (known != null && now - known.expiresAt < 0) return known.principal;
        }
        String username = credentials.substring(0, colon);
        PasswordStore.Account account = store.find(username);
        boolean matches = encoder.matches(credentials.substring(colon + 1),
                account == null ? unknownAccountHash : account.passwordHash());
        if (account == null || !matches) throw new AuthenticationFailedException(challenge);
        if (cacheSize > 0) synchronized (verified) {
            verified.put(key, new Verified(account.principal(), now + cacheNanos));
            if (verified.size() > cacheSize) verified.remove(verified.keySet().iterator().next());
        }
        return account.principal();
    }

    @Override
    public List<SecurityScheme> schemes() {
        return List.of(SecurityScheme.basic(realm));
    }

    private static byte[] sha256(String credentials) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(credentials.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
