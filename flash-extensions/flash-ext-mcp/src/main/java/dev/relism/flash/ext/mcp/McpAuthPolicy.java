package dev.relism.flash.ext.mcp;

import java.util.function.Supplier;

/**
 * Compiled per-tool authorization requirement, built once at boot by {@link McpOidcIntegration}
 * from {@code @RolesAllowed}/{@code @ScopesAllowed} on an {@link McpTool} subclass — {@code null}
 * on {@link McpRegistry.RegisteredTool} means no restriction beyond whatever {@link McpSecurity}
 * already enforces route-wide.
 *
 * <p>{@code check} is a closure, not a raw role/scope list — this is what lets this record (and
 * its only caller, {@link McpDispatcher}) stay free of any compile-time reference to a {@code
 * flash-ext-oidc} type, preserving the same classload isolation {@link McpOidcIntegration}'s
 * javadoc describes for the rest of the OIDC bridge. Only the plain-JDK {@link Supplier}
 * signature crosses the boundary; the closure itself, built once inside {@code
 * McpOidcIntegration}, is the only place that ever touches {@code OidcUser}/{@code ClaimsHolder}.
 *
 * <p>Returns {@code null} from {@link #check()}{@code .get()} when authorized, or a
 * human-readable denial reason otherwise — invoked once per {@code tools/call} against an
 * annotated tool, never allocated on that path (the closure and its captured role/scope arrays
 * are built exactly once, at boot).
 */
record McpAuthPolicy(Supplier<String> check) {}
