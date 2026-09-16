package dev.relism.flash.ext.security;

/** The application user a verified principal belongs to — typically found, or provisioned, by issuer and subject. */
@FunctionalInterface
public interface UserResolver<U> {

    U resolve(Principal principal);
}
