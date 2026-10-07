package dev.relism.flash.ext.security.form;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.security.LoginMethod;
import dev.relism.flash.ext.security.Redirects;
import dev.relism.flash.ext.security.Security;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.QueryParams;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;

/**
 * Password sign-in: {@code POST /auth/form/login} with {@code username} and {@code password}
 * form-encoded — what an HTML form sends, and what {@code fetch} sends given {@code URLSearchParams}.
 * Success starts a session and answers {@code 303} to {@code redirect} (same-origin paths only) or
 * {@code /}; failure is a {@code 401}, identical for an unknown account and a wrong password.
 *
 * <pre>{@code
 * app.install(new SecurityModule(), new FormLoginModule(accounts::byUsername));
 * }</pre>
 *
 * Rate-limit the route with {@code flash-ext-limiter}: each attempt costs a password hash, on
 * purpose, and this module does not bound how many a client may make.
 */
public final class FormLoginModule implements Module {

    public static final String LOGIN = "/auth/form/login";

    private final PasswordStore store;
    private PasswordEncoder encoder = PasswordEncoder.pbkdf2();

    public FormLoginModule(PasswordStore store) {
        this.store = store;
    }

    public FormLoginModule passwordEncoder(PasswordEncoder encoder) {
        this.encoder = encoder;
        return this;
    }

    @Override
    public void install(App app) {
        Login login = new Login(store, encoder);
        app.add(Login.class, login)
                .post(LOGIN, login)
                .add(LoginMethod.class, new LoginMethod("form", "Password", LOGIN, LoginMethod.Kind.FORM));
    }

    /** The login route, bound to the app's {@link Security} when it starts. */
    private static final class Login extends RequestHandler {

        private final PasswordStore store;
        private final PasswordEncoder encoder;
        private Security security;
        /** Checked against when no account matches, so an unknown username costs what a wrong password does. */
        private String unknownAccountHash;

        Login(PasswordStore store, PasswordEncoder encoder) {
            this.store = store;
            this.encoder = encoder;
        }

        @Override
        protected void onInit() {
            security = services().get(Security.class);
            unknownAccountHash = encoder.encode("unknown-account");
        }

        @Override
        protected Object handle(Request req, Response res) {
            QueryParams form = req.form();
            String username = form.get("username");
            String password = form.get("password");
            if (username == null || password == null) throw HttpException.badRequest("username and password are required");
            PasswordStore.Account account = store.find(username);
            boolean matches = encoder.matches(password, account == null ? unknownAccountHash : account.passwordHash());
            if (account == null || !matches) throw HttpException.unauthorized();
            security.signIn(req, res, account.principal());
            res.status(303).header("Location", Redirects.localPath(req.query("redirect")));
            return null;
        }
    }
}
