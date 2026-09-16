package dev.relism.flash.ext.security.oidc;

import dev.relism.flash.ext.security.Principal;

import java.util.Collection;
import java.util.Map;

/**
 * A caller an OpenID Provider vouched for: through a bearer access token, or by signing in. Its
 * {@link #name()} is the {@code sub} claim — unique per {@link #issuer()}, never across issuers.
 *
 * @param provider     the {@link OidcProvider#id()}
 * @param claims       verified: the access token's for a bearer caller, the ID token's for a session
 * @param refreshToken {@code null} for a bearer caller
 */
public record OidcPrincipal(String provider, String name, Map<String, Object> claims,
                            String accessToken, String refreshToken, String logoutUrl) implements Principal {

    public String issuer() {
        return (String) claims.get("iss");
    }

    public String email() {
        return (String) claims.get("email");
    }

    public Object claim(String name) {
        return claims.get(name);
    }

    /** From {@code scope} (space-delimited) or {@code scp}; a token that grants none grants none. */
    @Override
    public boolean hasScope(String scope) {
        Object granted = claims.containsKey("scope") ? claims.get("scope") : claims.get("scp");
        if (granted instanceof Collection<?> list) return list.contains(scope);
        if (!(granted instanceof String delimited)) return false;
        for (int at = delimited.indexOf(scope); at >= 0; at = delimited.indexOf(scope, at + 1)) {
            int end = at + scope.length();
            if ((at == 0 || delimited.charAt(at - 1) == ' ') && (end == delimited.length() || delimited.charAt(end) == ' ')) return true;
        }
        return false;
    }

    @Override
    public boolean hasAudience(String audience) {
        Object aud = claims.get("aud");
        return aud instanceof Collection<?> list ? list.contains(audience) : audience.equals(aud);
    }
}
