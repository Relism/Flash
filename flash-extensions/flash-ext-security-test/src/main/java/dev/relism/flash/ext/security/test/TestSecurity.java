package dev.relism.flash.ext.security.test;

import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.ext.security.SecurityExtension;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;
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
 * FlashTest app = FlashTest.of(flash -> flash.apply(new MyApp()).install(new TestSecurity()));
 * app.request().with(TestSecurity.as(() -> "alice")).get("/me");
 * }</pre>
 *
 * <p>Works because {@code FlashTest} serves the application in the test's own JVM: the principal
 * is handed over by reference, under a token only this process can have issued.
 */
public final class TestSecurity implements FlashExtension {

    private static final String SCHEME = "Test ";
    private static final Map<String, Principal> PRINCIPALS = new ConcurrentHashMap<>();

    /** Sends the request as {@code principal}. */
    public static Consumer<FlashRequest> as(Principal principal) {
        String token = UUID.randomUUID().toString();
        PRINCIPALS.put(token, principal);
        return request -> request.header("Authorization", SCHEME + token);
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        ctx.onReady(() -> ctx.require(SecurityExtension.class).mechanism(req -> {
            String header = req.header("Authorization");
            return header != null && header.startsWith(SCHEME) ? PRINCIPALS.get(header.substring(SCHEME.length())) : null;
        }));
    }
}
