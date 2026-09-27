package dev.relism.flash.ext.security;

import dev.relism.flash.http.HttpException;

/** A credential was presented and rejected. Stackless: turning away forged tokens stays cheap. */
public final class AuthenticationFailedException extends HttpException {

    private final String challenge;

    /** @param challenge the {@code WWW-Authenticate} value to answer with, or {@code null} */
    public AuthenticationFailedException(String challenge) {
        super(401, "Unauthorized");
        this.challenge = challenge;
        if (challenge != null) header("WWW-Authenticate", challenge);
    }

    public String challenge() {
        return challenge;
    }
}
