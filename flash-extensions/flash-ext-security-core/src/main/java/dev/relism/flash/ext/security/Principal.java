package dev.relism.flash.ext.security;

/**
 * Who an {@link AuthenticationMechanism} proved the caller to be. Each mechanism has its own type;
 * {@link SecurityIdentity#principal(Class)} reads it back.
 */
public interface Principal {

    /** Unique within the mechanism that produced it. */
    String name();

    /** Whether the credential grants {@code scope}. One that carries no scopes grants every scope. */
    default boolean hasScope(String scope) { return true; }

    /** Whether the credential was issued for {@code audience} (RFC 8707). One bound to no audience was. */
    default boolean hasAudience(String audience) { return true; }

    /** Where signing out sends the browser; {@code null} for the application root. */
    default String logoutUrl() { return null; }
}
