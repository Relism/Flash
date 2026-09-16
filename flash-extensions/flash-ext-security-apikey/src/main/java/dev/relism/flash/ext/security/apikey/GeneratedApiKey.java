package dev.relism.flash.ext.security.apikey;

/**
 * A freshly generated key. {@code token} goes to the caller exactly once; the application stores
 * {@code id} and {@code secretHash}, never the token.
 */
public record GeneratedApiKey(String id, String token, String secretHash) {}
