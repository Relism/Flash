package dev.relism.flash.ext.auth;

/**
 * Where authorization reads its inputs from. Deliberately small: everything about *obtaining* a
 * credential belongs to the {@link CredentialSource} that produced it, and everything about
 * *checking* one is right here.
 *
 * <p>Defaults are the generic spelling, not any one provider's. A source that knows better —
 * {@code flash-ext-auth-oidc} defaults roles to Keycloak's {@code realm_access.roles} — builds
 * its own {@code AuthConfig} with the paths its provider actually uses.
 */
public final class AuthConfig {

    private final String rolesClaimPath;
    private final String scopeClaimPaths;

    private AuthConfig(Builder b) {
        this.rolesClaimPath  = b.rolesClaimPath;
        this.scopeClaimPaths = b.scopeClaimPaths;
    }

    /** Dot-separated path to the roles list in the claims (default: {@code roles}). */
    public String rolesClaimPath() { return rolesClaimPath; }

    /** Comma-separated claim paths scopes are read from, in order (default: {@code scope,scp}). */
    public String scopeClaimPaths() { return scopeClaimPaths; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String rolesClaimPath  = "roles";
        private String scopeClaimPaths = "scope,scp";

        private Builder() {}

        /** Dot-separated path to the roles list — e.g. {@code realm_access.roles}, {@code groups}. */
        public Builder rolesClaimPath(String path)   { this.rolesClaimPath = path;   return this; }
        /** Comma-separated claim paths scopes are read from, tried in order. */
        public Builder scopeClaimPaths(String paths) { this.scopeClaimPaths = paths; return this; }

        public AuthConfig build() { return new AuthConfig(this); }
    }
}
