package dev.relism.flash.ext.security;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.routing.RouteHook;

import java.time.Duration;

/**
 * Flash security for an app: the {@link Security} service, the policies security annotations
 * declare, sessions, and the {@code /auth/logout} and {@code /auth/methods} routes. A way to
 * authenticate is an {@link AuthenticationMechanism} added to the app, by an app or by a module
 * such as {@code OidcModule}; they are tried in the order they were added, then the session.
 *
 * <pre>{@code
 * app.install(new SecurityModule().users(users).roles(roles).origin("https://app.example"),
 *             new OidcModule(OidcProvider.of("sso", issuer, clientId, secret)));
 * }</pre>
 */
public class SecurityModule implements Module {

    private final Security security = new Security(this);
    UserResolver<?> users = principal -> principal;
    RoleResolver roles;
    AuthenticationEntryPoint entryPoint;
    SessionStore sessions = new InMemorySessionStore();
    Duration sessionTimeout = Duration.ofHours(12);
    String loginPage;
    String origin;

    /** Resolves {@link SecurityIdentity#user} — default: the principal itself. */
    public SecurityModule users(UserResolver<?> users) {
        this.users = users;
        return this;
    }

    /** Required by {@link RolesAllowed}; a handler that declares roles without one fails the boot. */
    public SecurityModule roles(RoleResolver roles) {
        this.roles = roles;
        return this;
    }

    /** Replaces the default: a browser is redirected to sign in, anything else gets 401 with every challenge. */
    public SecurityModule entryPoint(AuthenticationEntryPoint entryPoint) {
        this.entryPoint = entryPoint;
        return this;
    }

    public SecurityModule sessions(SessionStore sessions) {
        this.sessions = sessions;
        return this;
    }

    public SecurityModule sessionTimeout(Duration sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
        return this;
    }

    /**
     * Where a browser signs in. Unset: straight to the only {@link LoginMethod} when it is a redirect, {@code /login}
     * otherwise. Set it when the application's page knows ways in the list does not, like a provider picked by email.
     */
    public SecurityModule loginPage(String loginPage) {
        this.loginPage = loginPage;
        return this;
    }

    /**
     * Where the application is served, e.g. {@code https://app.example}: what session cookies, sign-in
     * callbacks and token audiences are built from. Unset, each request's own origin is used, which
     * the client chooses — set it wherever that matters, which is every deployment.
     */
    public SecurityModule origin(String origin) {
        this.origin = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        return this;
    }

    /**
     * The service this module installs, for what is written before the app starts: a route's own
     * {@code security.enforce(...)} middleware, a lambda that signs a user in. A class takes it as a
     * constructor parameter instead.
     */
    public Security security() {
        return security;
    }

    @Override
    public void install(App app) {
        app.add(Security.class, security)
                .add(RouteHook.class, security)
                .post("/auth/logout", (req, res) -> {
                    res.status(303).header("Location", security.signOut(req, res));
                    return null;
                })
                .get("/auth/methods", (req, res) -> {
                    res.type(ContentType.JSON);
                    return security.methodsJson();
                })
                .install(Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                        () -> a -> a.add(OpenApiContributor.class, SecurityDocs.class)));
    }
}
