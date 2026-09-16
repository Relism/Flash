package dev.relism.flash.ext.security;

/** A way to sign in, listed at {@code GET /auth/methods} for a client to offer. */
public record LoginMethod(String id, String name, String url, Kind kind) {

    public enum Kind {
        /** A browser navigates to {@code url} and comes back signed in — OpenID Connect, for instance. */
        REDIRECT,
        /** A client posts {@code username} and {@code password} to {@code url}. */
        FORM
    }
}
