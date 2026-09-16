package dev.relism.flash.ext.security;

/** Renews an expired session — with a refresh token, typically. {@code null} ends it instead. */
@FunctionalInterface
public interface SessionRefresher {

    Session refresh(Session expired);
}
