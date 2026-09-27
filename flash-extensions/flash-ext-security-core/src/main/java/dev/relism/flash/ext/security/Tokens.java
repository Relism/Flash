package dev.relism.flash.ext.security;

import java.security.SecureRandom;
import java.util.Base64;

/** Unguessable identifiers: session ids, sign-in state, PKCE verifiers, API key parts. */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private Tokens() {}

    /** {@code bytes} random bytes, base64url without padding. */
    public static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return BASE64URL.encodeToString(value);
    }
}
