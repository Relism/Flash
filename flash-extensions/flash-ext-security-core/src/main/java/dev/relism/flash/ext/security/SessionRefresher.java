package dev.relism.flash.ext.security;

/**
 * Renews an expired session for principals of one type, e.g. with the refresh token an identity
 * provider issued. Added to the app as a service; {@link Security} asks the one matching the
 * session's principal.
 */
public interface SessionRefresher {

    /** The principal class this refresher renews. */
    Class<? extends Principal> type();

    /** The renewed session, or {@code null} when it cannot be renewed and ends. */
    Session refresh(Session expired);
}
