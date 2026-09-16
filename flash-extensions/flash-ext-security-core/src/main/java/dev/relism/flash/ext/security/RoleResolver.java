package dev.relism.flash.ext.security;

/**
 * Whether a caller holds a role — read from a token, a database, anywhere. {@code on} identifies
 * the resource for roles held per resource rather than globally.
 */
@FunctionalInterface
public interface RoleResolver {

    boolean hasRole(SecurityIdentity identity, String role, Target on);
}
