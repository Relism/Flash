package dev.relism.flash.ext.mcp;

/**
 * OAuth2 requirement policy for the MCP endpoint, resolved against whether
 * {@code flash-ext-oidc} is installed ({@code ctx.find(OidcMiddleware.class)}).
 */
public enum McpSecurity {

    /** Fail fast at boot if {@code flash-ext-oidc} is not installed — never expose an unprotected MCP endpoint. */
    REQUIRED,

    /** Protect the endpoint if {@code flash-ext-oidc} is installed; otherwise run unprotected and log a warning. */
    AUTO,

    /** Never protect the endpoint, even if {@code flash-ext-oidc} is installed elsewhere in the app. */
    NONE
}
