package dev.relism.flash.ext.security;

import dev.relism.flash.Bound;
import dev.relism.flash.Services;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.Middleware;
import dev.relism.flash.routing.MiddlewareKey;
import dev.relism.flash.routing.MiddlewareNode;
import dev.relism.flash.routing.RouteHook;
import dev.relism.fpr.core.ByteView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The authentication chain of one app: who the caller is, what the security annotations on a
 * handler demand of them, and their session. Installed by {@link SecurityModule}; a handler or a
 * module takes it as a constructor parameter.
 *
 * <p>Every {@link AuthenticationMechanism}, {@link LoginMethod} and {@link SessionRefresher} the
 * app adds is read once, when it starts: nothing registers with this service by hand.
 */
public final class Security implements Bound, RouteHook {

    /** The node security annotations mount under, for middleware that must run before or after it. */
    public static final MiddlewareKey POLICY = MiddlewareKey.of("flash.security.policy");

    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";
    private static final String COOKIE = "flash_session";

    private final SecurityModule config;
    UserResolver<?> users;
    RoleResolver roles;
    private AuthenticationEntryPoint entryPoint;
    private SessionStore sessions;
    private Duration sessionTimeout;
    private String loginPage;
    private String origin;

    private AuthenticationMechanism[] mechanisms = {};
    private List<SecurityScheme> schemes = List.of();
    private LoginMethod[] loginMethods = {};
    private final Map<Class<?>, SessionRefresher> refreshers = new HashMap<>();
    private String challenges;
    private byte[] methodsJson;

    Security(SecurityModule config) {
        this.config = config;
    }

    /** Reads the module's configuration and what the app added, once, and encodes what never changes after. */
    @Override
    public void bind(Services services) {
        users = config.users;
        roles = config.roles;
        entryPoint = config.entryPoint != null ? config.entryPoint : this::commence;
        sessions = config.sessions;
        sessionTimeout = config.sessionTimeout;
        loginPage = config.loginPage;
        origin = config.origin;

        mechanisms = services.all(AuthenticationMechanism.class).toArray(AuthenticationMechanism[]::new);
        loginMethods = services.all(LoginMethod.class).toArray(LoginMethod[]::new);
        for (SessionRefresher refresher : services.all(SessionRefresher.class)) refreshers.put(refresher.type(), refresher);

        List<SecurityScheme> all = new ArrayList<>();
        StringBuilder challenge = new StringBuilder();
        for (AuthenticationMechanism mechanism : mechanisms) {
            for (SecurityScheme scheme : mechanism.schemes()) {
                all.add(scheme);
                challenge.append(challenge.isEmpty() ? "" : ", ").append(scheme.challenge());
            }
        }
        schemes = List.copyOf(all);
        challenges = challenge.isEmpty() ? null : challenge.toString();

        StringBuilder json = new StringBuilder("[");
        for (LoginMethod method : loginMethods) {
            if (json.length() > 1) json.append(',');
            json.append("{\"id\":").append(quoted(method.id())).append(",\"name\":").append(quoted(method.name()))
                    .append(",\"url\":").append(quoted(method.url())).append(",\"kind\":\"")
                    .append(method.kind().name().toLowerCase()).append("\"}");
        }
        methodsJson = json.append(']').toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Every security annotation on a handler becomes this middleware; declaring roles without a {@link RoleResolver} fails the boot. */
    @Override
    public List<MiddlewareNode> middleware(Class<?> handler) {
        SecurityPolicy policy = policy(handler);
        return policy == null ? List.of() : List.of(MiddlewareNode.of(POLICY, enforce(policy)));
    }

    /** The schemes of every mechanism, in the order the mechanisms were added. */
    public List<SecurityScheme> schemes() {
        return schemes;
    }

    /** The configured origin, or the one {@code req} names when none is. */
    public String origin(Request req) {
        return origin != null ? origin : req.origin();
    }

    /**
     * The caller, or {@code null} when no mechanism recognises a credential.
     *
     * @throws AuthenticationFailedException a mechanism recognised one and rejected it
     */
    public SecurityIdentity authenticate(Request req) {
        return authenticate(req, null);
    }

    /** {@code only} restricts the chain to those mechanisms, without the session; {@code null} is every one of them and the session. */
    private SecurityIdentity authenticate(Request req, AuthenticationMechanism[] only) {
        for (AuthenticationMechanism mechanism : only != null ? only : mechanisms) {
            Principal principal = mechanism.authenticate(req);
            if (principal != null) return new SecurityIdentity(principal, this, req);
        }
        Principal principal = only != null ? null : sessionPrincipal(req);
        return principal == null ? null : new SecurityIdentity(principal, this, req);
    }

    /** The policy {@code type}'s annotations declare, checked against this configuration; {@code null} for none. */
    public SecurityPolicy policy(Class<?> type) {
        SecurityPolicy policy = SecurityPolicy.of(type);
        if (policy != null && policy.requiresRoles() && roles == null) {
            throw new IllegalStateException(type.getName() + " declares @RolesAllowed, but no RoleResolver is configured — SecurityModule.roles(...)");
        }
        return policy;
    }

    public Middleware enforce(SecurityPolicy policy) {
        return next -> guard(policy, entryPoint, null).wrap(next);
    }

    /** {@code anonymous} answers a caller without credentials on this route instead of the configured entry point. */
    public Middleware enforce(SecurityPolicy policy, AuthenticationEntryPoint anonymous) {
        return guard(policy, anonymous, null);
    }

    /**
     * A route only {@code mechanisms} authenticate: every other credential, the session cookie included,
     * is no credential at all here — a bearer route that must not be reached with a browser's cookie or
     * with another mechanism's token.
     */
    public Middleware enforce(SecurityPolicy policy, AuthenticationEntryPoint anonymous, List<AuthenticationMechanism> mechanisms) {
        return guard(policy, anonymous, mechanisms.toArray(AuthenticationMechanism[]::new));
    }

    private Middleware guard(SecurityPolicy policy, AuthenticationEntryPoint anonymous, AuthenticationMechanism[] only) {
        return next -> (req, res) -> {
            SecurityIdentity identity;
            try {
                identity = authenticate(req, only);
            } catch (AuthenticationFailedException rejected) {
                if (policy.required) throw rejected;
                identity = null;
            }
            if (identity == null) {
                if (policy.required) return anonymous.commence(req, res);
            } else if (!policy.permitsScopes(identity)) {
                throw HttpException.forbidden().header(WWW_AUTHENTICATE, policy.scopeChallenge);
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
        Session session = new Session(Tokens.random(24), principal, expiresAt);
        sessions.save(session);
        res.header("Set-Cookie", COOKIE + "=" + session.id() + "; Path=/; HttpOnly; SameSite=Lax" + (origin(req).startsWith("https") ? "; Secure" : ""));
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

    /** The ways in, as the JSON {@code /auth/methods} answers: encoded once, at start. */
    byte[] methodsJson() {
        return methodsJson;
    }

    private Object commence(Request req, Response res) {
        LoginMethod[] methods = loginMethods;
        String accept = req.header("Accept");
        if ((loginPage != null || methods.length > 0) && accept != null && accept.contains("text/html")) {
            String login = loginPage != null ? loginPage
                    : methods.length == 1 && methods[0].kind() == LoginMethod.Kind.REDIRECT ? methods[0].url() : "/login";
            ByteView query = req.rawQuery();
            String target = query == null || query.length() == 0 ? req.path() : req.path() + "?" + ascii(query);
            res.redirect(login + "?redirect=" + URLEncoder.encode(target, StandardCharsets.UTF_8));
            return null;
        }
        HttpException refused = HttpException.unauthorized();
        throw challenges == null ? refused : refused.header(WWW_AUTHENTICATE, challenges);
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

    private static String ascii(ByteView bytes) {
        byte[] copy = new byte[bytes.length()];
        for (int i = 0; i < copy.length; i++) copy[i] = bytes.byteAt(i);
        return new String(copy, StandardCharsets.UTF_8);
    }

    private static String quoted(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
