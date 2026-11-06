package dev.relism.flash.ext.security;

import dev.relism.flash.http.Request;

/**
 * Where signing out sends the browser, for principals of one type whose way out is built as they
 * leave: a logout request to the identity provider, signed and dated then. Added to the app as a
 * service; {@link Security#signOut} asks the one matching the principal, or else reads
 * {@link Principal#logoutUrl()}.
 */
public interface SessionLogout {

    /** The principal class this logout ends sessions of. */
    Class<? extends Principal> type();

    /** Where the browser goes now that {@code ended} is gone; {@code null} for the application root. */
    String logoutUrl(Session ended, Request req);
}
