package dev.relism.flash.ext.security;

import dev.relism.flash.http.Request;

/**
 * The authenticated caller of the current request: the {@link Principal} a mechanism produced, the
 * application user it resolves to, and the roles and scopes it holds.
 */
public final class SecurityIdentity {

    static final ThreadLocal<SecurityIdentity> CURRENT = new ThreadLocal<>();

    private final Principal principal;
    private final Security security;
    private final Request request;
    private Object user;

    SecurityIdentity(Principal principal, Security security, Request request) {
        this.principal = principal;
        this.security = security;
        this.request = request;
    }

    /** The caller of the request this thread is handling; {@code null} when it is anonymous. */
    public static SecurityIdentity current() {
        return CURRENT.get();
    }

    public Principal principal() {
        return principal;
    }

    /**
     * The request being authorized. {@link Target} carries what {@link RolesAllowed#on()} names, read
     * from path and query parameters; a {@link RoleResolver} whose scope is somewhere else — a tenant
     * header, say — reads it from here.
     */
    public Request request() {
        return request;
    }

    /** The principal as {@code type}, or {@code null} when another mechanism authenticated the caller. */
    public <P extends Principal> P principal(Class<P> type) {
        return type.isInstance(principal) ? type.cast(principal) : null;
    }

    /** The application user, resolved once per request by the configured {@link UserResolver}. */
    public <U> U user(Class<U> type) {
        if (user == null) user = security.users.resolve(principal);
        return type.cast(user);
    }

    public boolean hasScope(String scope) {
        return principal.hasScope(scope);
    }

    public boolean hasRole(String role) {
        return hasRole(role, Target.NONE);
    }

    public boolean hasRole(String role, Target on) {
        if (security.roles == null) throw new IllegalStateException("No RoleResolver configured — SecurityModule.roles(...)");
        return security.roles.hasRole(this, role, on);
    }
}
