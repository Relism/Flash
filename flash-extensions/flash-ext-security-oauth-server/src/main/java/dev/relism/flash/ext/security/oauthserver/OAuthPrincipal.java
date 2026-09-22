package dev.relism.flash.ext.security.oauthserver;

import dev.relism.flash.ext.security.Principal;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A caller holding an access token this application issued. {@link #name()} is {@code sub}: the
 * {@link OAuthSubject#id()} the user authorized as, or the client's own id for {@code client_credentials}.
 *
 * @param claims the token's verified claims
 */
public record OAuthPrincipal(String name, String clientId, Map<String, Object> claims) implements Principal {

    public Object claim(String name) {
        return claims.get(name);
    }

    @Override
    public boolean hasScope(String scope) {
        return claims.get("scope") instanceof String granted && List.of(granted.split(" ")).contains(scope);
    }

    @Override
    public boolean hasAudience(String audience) {
        Object aud = claims.get("aud");
        return aud instanceof Collection<?> list ? list.contains(audience) : audience.equals(aud);
    }
}
