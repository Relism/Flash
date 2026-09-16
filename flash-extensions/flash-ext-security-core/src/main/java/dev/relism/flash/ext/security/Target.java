package dev.relism.flash.ext.security;

/**
 * The resource a role is checked on: the values {@link RolesAllowed#on()} names, read from path
 * and query parameters on HTTP and from tool arguments on MCP.
 */
@FunctionalInterface
public interface Target {

    /** A role checked on nothing in particular. */
    Target NONE = name -> null;

    /** The value called {@code name}, or {@code null} when the call does not carry one. */
    String get(String name);
}
