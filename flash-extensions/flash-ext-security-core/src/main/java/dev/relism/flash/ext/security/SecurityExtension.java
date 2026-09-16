package dev.relism.flash.ext.security;

import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.openapi.OpenApiContributorRegistry;
import dev.relism.flash.ext.openapi.OpenApiOperationContribution;
import dev.relism.flash.ext.openapi.OpenApiResponseContribution;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.Middleware;
import dev.relism.flash.routing.MiddlewareKey;
import dev.relism.flash.routing.MiddlewareNode;
import dev.relism.fpr.core.ByteView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flash security: the authentication chain, the policies security annotations declare, sessions,
 * and the {@code /auth/logout} and {@code /auth/methods} routes. Mechanisms register through
 * {@link #mechanism}, directly or from their own extensions, and are tried in registration order
 * before the session cookie.
 *
 * <pre>{@code
 * app.install(new SecurityExtension().users(users).roles(roles))
 *    .install(new OidcExtension(OidcProvider.of("sso", issuer, clientId, secret)));
 * }</pre>
 */
public class SecurityExtension implements FlashExtension {

    /** The node security annotations mount under, for middleware that must run before or after it. */
    public static final MiddlewareKey POLICY = MiddlewareKey.of("flash.security.policy");

    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";
    private static final String COOKIE = "flash_session";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<Class<?>, SessionRefresher> refreshers = new ConcurrentHashMap<>();
    private volatile AuthenticationMechanism[] mechanisms = {};
    private volatile SecurityScheme[] schemes = {};
    private volatile LoginMethod[] loginMethods = {};
    private volatile String challenges;
    private volatile String methodsJson = "[]";

    UserResolver<?> users = principal -> principal;
    RoleResolver roles;
    private AuthenticationEntryPoint entryPoint = this::commence;
    private SessionStore sessions = new InMemorySessionStore();
    private Duration sessionTimeout = Duration.ofHours(12);
    private String loginPage = "/login";

    // -- Configuration --------------------------------------------------------

    /** Resolves {@link SecurityIdentity#user} — default: the principal itself. */
    public SecurityExtension users(UserResolver<?> users) {
        this.users = users;
        return this;
    }

    /** Required by {@link RolesAllowed}; a handler that declares roles without one fails the boot. */
    public SecurityExtension roles(RoleResolver roles) {
        this.roles = roles;
        return this;
    }

    /** Replaces the default: a browser is redirected to sign in, anything else gets 401 with every challenge. */
    public SecurityExtension entryPoint(AuthenticationEntryPoint entryPoint) {
        this.entryPoint = entryPoint;
        return this;
    }

    public SecurityExtension sessions(SessionStore sessions) {
        this.sessions = sessions;
        return this;
    }

    public SecurityExtension sessionTimeout(Duration sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
        return this;
    }

    /** Where a browser signs in, unless the only {@link LoginMethod} is a redirect it can follow directly. */
    public SecurityExtension loginPage(String loginPage) {
        this.loginPage = loginPage;
        return this;
    }

    // -- Registration (boot time) ---------------------------------------------

    public synchronized SecurityExtension mechanism(AuthenticationMechanism mechanism) {
        mechanisms = append(mechanisms, mechanism);
        return mechanism.scheme() == null ? this : scheme(mechanism.scheme());
    }

    /** Documents and challenges for a credential beyond the one {@link AuthenticationMechanism#scheme()} names. */
    public synchronized SecurityExtension scheme(SecurityScheme scheme) {
        schemes = append(schemes, scheme);
        challenges = challenges == null ? scheme.challenge() : challenges + ", " + scheme.challenge();
        return this;
    }

    public synchronized SecurityExtension loginMethod(LoginMethod method) {
        loginMethods = append(loginMethods, method);
        StringBuilder json = new StringBuilder("[");
        for (LoginMethod m : loginMethods) {
            if (json.length() > 1) json.append(',');
            json.append("{\"id\":\"").append(m.id()).append("\",\"name\":\"").append(m.name())
                .append("\",\"url\":\"").append(m.url()).append("\",\"kind\":\"").append(m.kind().name().toLowerCase()).append("\"}");
        }
        methodsJson = json.append(']').toString();
        return this;
    }

    public SecurityExtension refresher(Class<? extends Principal> type, SessionRefresher refresher) {
        refreshers.put(type, refresher);
        return this;
    }

    /** The schemes of every registered mechanism, in registration order. */
    public List<SecurityScheme> schemes() {
        return List.of(schemes);
    }

    // -- Runtime --------------------------------------------------------------

    /**
     * The caller, or {@code null} when no mechanism recognises a credential.
     *
     * @throws AuthenticationFailedException a mechanism recognised one and rejected it
     */
    public SecurityIdentity authenticate(Request req) {
        for (AuthenticationMechanism mechanism : mechanisms) {
            Principal principal = mechanism.authenticate(req);
            if (principal != null) return new SecurityIdentity(principal, this, req);
        }
        Principal principal = sessionPrincipal(req);
        return principal == null ? null : new SecurityIdentity(principal, this, req);
    }

    /**
     * The policy {@code type}'s annotations declare, checked against this configuration — declaring
     * roles without a {@link RoleResolver} fails here, at boot. {@code null} for no annotations.
     */
    public SecurityPolicy policy(Class<?> type) {
        SecurityPolicy policy = SecurityPolicy.of(type);
        if (policy != null && policy.requiresRoles() && roles == null) {
            throw new IllegalStateException(type.getName() + " declares @RolesAllowed, but no RoleResolver is configured — SecurityExtension.roles(...)");
        }
        return policy;
    }

    public Middleware enforce(SecurityPolicy policy) {
        return enforce(policy, entryPoint);
    }

    /** {@code anonymous} answers a caller without credentials on this route instead of the configured entry point. */
    public Middleware enforce(SecurityPolicy policy, AuthenticationEntryPoint anonymous) {
        return next -> (req, res) -> {
            SecurityIdentity identity;
            try {
                identity = authenticate(req);
            } catch (AuthenticationFailedException rejected) {
                if (policy.required) {
                    if (rejected.challenge() != null) res.header(WWW_AUTHENTICATE, rejected.challenge());
                    throw rejected;
                }
                identity = null;
            }
            if (identity == null) {
                if (policy.required) return anonymous.commence(req, res);
            } else if (!policy.permitsScopes(identity)) {
                res.header(WWW_AUTHENTICATE, policy.scopeChallenge);
                throw HttpException.forbidden();
            } else if (!policy.permitsRoles(identity, policy.on.length == 0 ? Target.NONE : name -> {
                String value = req.param(name);
                return value != null ? value : req.query(name);
            })) {
                throw HttpException.forbidden();
            }
            SecurityIdentity.CURRENT.set(identity);
            try {
                return next.handle(req, res);
            } finally {
                SecurityIdentity.CURRENT.remove();
            }
        };
    }

    /** Starts a session for {@code principal} lasting the configured timeout. */
    public void signIn(Request req, Response res, Principal principal) {
        signIn(req, res, principal, Instant.now().plus(sessionTimeout));
    }

    public void signIn(Request req, Response res, Principal principal, Instant expiresAt) {
        byte[] id = new byte[24];
        RANDOM.nextBytes(id);
        Session session = new Session(Base64.getUrlEncoder().withoutPadding().encodeToString(id), principal, expiresAt);
        sessions.save(session);
        res.header("Set-Cookie", COOKIE + "=" + session.id() + "; Path=/; HttpOnly; SameSite=Lax" + (req.origin().startsWith("https") ? "; Secure" : ""));
    }

    /** Ends the caller's session and returns where the browser goes next. */
    public String signOut(Request req, Response res) {
        String id = req.cookie(COOKIE);
        Session session = id == null ? null : sessions.find(id);
        if (session != null) sessions.delete(id);
        res.header("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        String next = session == null ? null : session.principal().logoutUrl();
        return next == null ? "/" : next;
    }

    // -- Extension ------------------------------------------------------------

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        ctx.provide(SecurityExtension.class, this);
        ctx.addAnnotationProcessor(handler -> {
            SecurityPolicy policy = policy(handler);
            return policy == null ? List.of() : List.of(MiddlewareNode.of(POLICY, enforce(policy)));
        });
        app.post("/auth/logout", (req, res) -> {
            res.status(303).header("Location", signOut(req, res));
            return null;
        });
        app.get("/auth/methods", (req, res) -> {
            res.type(ContentType.JSON);
            return methodsJson;
        });
        ctx.onReady(() -> {
            try {
                OpenApi.register(ctx, this);
            } catch (NoClassDefFoundError absent) {
                // flash-ext-openapi is not on the classpath
            }
        });
    }

    private Object commence(Request req, Response res) {
        LoginMethod[] methods = loginMethods;
        String accept = req.header("Accept");
        if (methods.length > 0 && accept != null && accept.contains("text/html")) {
            String login = methods.length == 1 && methods[0].kind() == LoginMethod.Kind.REDIRECT ? methods[0].url() : loginPage;
            ByteView query = req.getRequestLine().getQuery();
            byte[] raw = new byte[query == null ? 0 : query.length()];
            for (int i = 0; i < raw.length; i++) raw[i] = query.byteAt(i);
            String target = raw.length == 0 ? req.path() : req.path() + "?" + new String(raw, StandardCharsets.UTF_8);
            res.redirect(login + "?redirect=" + URLEncoder.encode(target, StandardCharsets.UTF_8));
            return null;
        }
        if (challenges != null) res.header(WWW_AUTHENTICATE, challenges);
        throw HttpException.unauthorized();
    }

    private Principal sessionPrincipal(Request req) {
        String id = req.cookie(COOKIE);
        Session session = id == null ? null : sessions.find(id);
        if (session == null) return null;
        if (session.expiresAt().toEpochMilli() > System.currentTimeMillis()) return session.principal();
        SessionRefresher refresher = refreshers.get(session.principal().getClass());
        Session renewed = refresher == null ? null : refresher.refresh(session);
        if (renewed == null) {
            sessions.delete(id);
            return null;
        }
        sessions.save(renewed);
        return renewed.principal();
    }

    private static <T> T[] append(T[] array, T element) {
        T[] grown = Arrays.copyOf(array, array.length + 1);
        grown[array.length] = element;
        return grown;
    }

    /** Isolated so this extension loads without flash-ext-openapi on the classpath. */
    private static final class OpenApi {

        static void register(FlashContext ctx, SecurityExtension security) {
            ctx.find(OpenApiContributorRegistry.class).ifPresent(registry -> registry.add(new OpenApiContributor() {
                @Override
                public Map<String, Object> componentContributions() {
                    Map<String, Object> definitions = new LinkedHashMap<>();
                    for (SecurityScheme scheme : security.schemes) definitions.put(scheme.name(), scheme.definition());
                    return definitions.isEmpty() ? Map.of() : Map.of("securitySchemes", definitions);
                }

                @Override
                public OpenApiOperationContribution operationFor(Class<?> handler) {
                    SecurityPolicy policy = SecurityPolicy.of(handler);
                    if (policy == null || !policy.required) return OpenApiOperationContribution.empty();
                    OpenApiOperationContribution.Builder operation = OpenApiOperationContribution.builder();
                    for (SecurityScheme scheme : security.schemes) {
                        operation.security(scheme.name(), scheme.issuer() != null ? List.of(policy.scopes) : List.of());
                    }
                    operation.response(401, OpenApiResponseContribution.of("Authentication required"));
                    String roles = policy.roles.length == 0 ? null : "Requires role " + String.join(" or ", policy.roles)
                            + (policy.on.length == 0 ? "" : " on " + String.join(", ", policy.on));
                    String scopes = policy.scopes.length == 0 ? null : "Requires scopes " + String.join(" ", policy.scopes);
                    if (roles != null || scopes != null) {
                        operation.response(403, OpenApiResponseContribution.of(
                                roles == null ? scopes : scopes == null ? roles : roles + "; " + scopes));
                    }
                    return operation.build();
                }
            }));
        }
    }
}
