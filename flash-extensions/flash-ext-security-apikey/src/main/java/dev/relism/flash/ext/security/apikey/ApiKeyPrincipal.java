package dev.relism.flash.ext.security.apikey;

import dev.relism.flash.ext.security.Principal;

/** A caller authenticated by an API key, carrying the grant the key was issued with. */
public record ApiKeyPrincipal<G>(String name, G grant) implements Principal {}
