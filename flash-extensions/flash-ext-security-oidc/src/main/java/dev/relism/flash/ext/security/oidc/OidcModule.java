package dev.relism.flash.ext.security.oidc;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.security.AuthenticationMechanism;
import dev.relism.flash.ext.security.LoginMethod;
import dev.relism.flash.ext.security.SessionRefresher;

/**
 * OpenID Connect for an app: the {@link Oidc} service as a way to authenticate and to renew
 * sessions, a sign-in for each configured provider, and the {@code /auth/oidc/{provider}/login}
 * and {@code /callback} routes. Needs {@code SecurityModule} beside it.
 *
 * <pre>{@code
 * app.install(new SecurityModule(), new OidcModule(OidcProvider.of("sso", "https://id.example.com/realms/acme", "app", secret)));
 * }</pre>
 *
 * <p>Configured providers are discovered when the server starts.
 */
public final class OidcModule implements Module {

    private final OidcProvider[] providers;
    private final Oidc oidc;

    public OidcModule(OidcProvider... providers) {
        this.providers = providers;
        this.oidc = new Oidc(providers);
    }

    /**
     * The service this module installs, for what is written before the app starts — naming it as
     * the only mechanism of a route, say. A class takes it as a constructor parameter instead.
     */
    public Oidc oidc() {
        return oidc;
    }

    /**
     * Lets providers registered at runtime live on private or loopback addresses and plain http:
     * development only, since otherwise registering one is a request forgery against the server's
     * own network.
     */
    public OidcModule allowLocalProviders() {
        oidc.localProviders = true;
        return this;
    }

    @Override
    public void install(App app) {
        app.add(Oidc.class, oidc)
                .add(AuthenticationMechanism.class, oidc)
                .add(SessionRefresher.class, oidc)
                .get("/auth/oidc/{provider}/login", oidc::login)
                .get("/auth/oidc/{provider}/callback", oidc::callback);
        for (OidcProvider provider : providers) {
            app.add(LoginMethod.class, new LoginMethod(provider.id(), provider.name(),
                    "/auth/oidc/" + provider.id() + "/login", LoginMethod.Kind.REDIRECT));
        }
    }
}
