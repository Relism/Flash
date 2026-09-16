package dev.relism.flash.ext.security;

import java.time.Instant;

/** A signed-in principal, kept server-side under the id its cookie carries. */
public record Session(String id, Principal principal, Instant expiresAt) {}
