package dev.relism.flash.ext.security.form;

import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.ext.security.LoginMethod;
import dev.relism.flash.ext.security.SecurityExtension;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Password sign-in: {@code POST /auth/form/login} with {@code username} and {@code password}
 * form-encoded — what an HTML form sends, and what {@code fetch} sends given {@code URLSearchParams}.
 * Success starts a session and answers {@code 303} to {@code redirect} (same-origin paths only) or
 * {@code /}; failure is a {@code 401}, identical for an unknown account and a wrong password.
 *
 * <pre>{@code
 * app.install(new SecurityExtension())
 *    .install(new FormLoginExtension(accounts::byUsername));
 * }</pre>
 *
 * Rate-limit the route with {@code flash-ext-limiter}; this extension does not.
 */
public final class FormLoginExtension implements FlashExtension {

    public static final String LOGIN = "/auth/form/login";

    private final PasswordStore store;
    private PasswordEncoder encoder = PasswordEncoder.pbkdf2();
    private String unknownAccountHash;

    public FormLoginExtension(PasswordStore store) {
        this.store = store;
    }

    public FormLoginExtension passwordEncoder(PasswordEncoder encoder) {
        this.encoder = encoder;
        return this;
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        // Checked against when no account matches, so an unknown username costs what a wrong password does.
        unknownAccountHash = encoder.encode("unknown-account");
        app.post(LOGIN, (req, res) -> {
            String body = new String(req.body().bytes(), StandardCharsets.UTF_8);
            String username = field(body, "username");
            String password = field(body, "password");
            if (username == null || password == null) throw HttpException.badRequest("username and password are required");

            PasswordStore.Account account = store.find(username);
            boolean matches = encoder.matches(password, account == null ? unknownAccountHash : account.passwordHash());
            if (account == null || !matches) throw HttpException.unauthorized();

            ctx.require(SecurityExtension.class).signIn(req, res, account.principal());
            String redirect = req.query("redirect");
            boolean local = redirect != null && redirect.startsWith("/") && !redirect.startsWith("//") && !redirect.startsWith("/\\");
            res.status(303).header("Location", local ? redirect : "/");
            return null;
        });
        ctx.onReady(() -> ctx.require(SecurityExtension.class)
                .loginMethod(new LoginMethod("form", "Password", LOGIN, LoginMethod.Kind.FORM)));
    }

    private static String field(String body, String name) {
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq == name.length() && pair.startsWith(name)) return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }
}
