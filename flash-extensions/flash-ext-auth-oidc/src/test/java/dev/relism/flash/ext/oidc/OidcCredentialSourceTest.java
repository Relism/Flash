package dev.relism.flash.ext.oidc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What stayed behind when authorization moved to {@code flash-ext-auth-core}: reading a bearer
 * token off the wire, and the RFC 6750 challenges this source answers with. The matching of
 * claims those credentials produce is {@code ClaimMatchingTest}'s job now.
 */
class OidcCredentialSourceTest {

    private static OidcCredentialSource source() {
        return new OidcCredentialSource(null, OidcConfig
                .builder("https://idp.example.com", "client", "secret", "/auth/callback")
                .build(), null, null);
    }

    @Test
    void extractBearerToken_acceptsCaseInsensitiveBearerAndTrimsSpaces() {
        assertEquals("abc.def.ghi", OidcCredentialSource.extractBearerToken("Bearer abc.def.ghi"));
        assertEquals("abc", OidcCredentialSource.extractBearerToken("   bearer    abc   "));
        assertNull(OidcCredentialSource.extractBearerToken("Basic Zm9vOmJhcg=="));
        assertNull(OidcCredentialSource.extractBearerToken("Bearer"));
    }

    @Test
    void bearerChallenge_containsRealmAndRfcErrors() {
        OidcCredentialSource src = source();

        String basic = src.bearerChallenge();
        String invalid = src.invalidTokenChallenge();
        String insufficient = src.insufficientScopeChallenge(new String[]{"orders:read", "payments:write"});

        assertTrue(basic.startsWith("Bearer realm=\""));
        assertTrue(invalid.contains("error=\"invalid_token\""));
        assertTrue(insufficient.contains("error=\"insufficient_scope\""));
        assertTrue(insufficient.contains("scope=\"orders:read payments:write\""));
    }
}
