package dev.relism.flash.ext.security.test;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.security.AuthenticationMechanism;
import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.http.Request;
import dev.relism.flash.testing.FlashRequest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Authenticates test requests as any {@link Principal}, whatever mechanisms the application
 * installs — an OIDC user, an API key, a plain name — without an identity provider.
 *
 * <pre>{@code
 * static final TestSecurity users = new TestSecurity();
 * static FlashTest app = FlashTest.of(() -> Main.api(db).install(users));
 *
 * app.request().with(users.as(() -> "alice")).get("/me");
 * }</pre>
 *
 * <p>Works because {@code FlashTest} serves the application in the test's own JVM: the principal
 * is handed over by reference, under a token only this instance issued.
 */
public final class TestSecurity implements Module, AuthenticationMechanism {

    private static final String SCHEME = "Test ";

    private final Map<String, Principal> principals = new ConcurrentHashMap<>();

    /** A request customiser that makes the request arrive as {@code principal}. */
    public Consumer<FlashRequest> as(Principal principal) {
        String token = UUID.randomUUID().toString();
        principals.put(token, principal);
        return request -> request.header("Authorization", SCHEME + token);
    }

    @Override
    public void install(App app) {
        app.add(AuthenticationMechanism.class, this);
    }

    @Override
    public Principal authenticate(Request req) {
        String header = req.header("Authorization");
        return header != null && header.startsWith(SCHEME) ? principals.get(header.substring(SCHEME.length())) : null;
    }
}
