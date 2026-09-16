package dev.relism.flash.ext.security.form;

import dev.relism.flash.ext.security.Principal;

/** Where the application keeps the accounts that sign in with a password. */
@FunctionalInterface
public interface PasswordStore {

    /** The account signing in as {@code username}, or {@code null}. */
    Account find(String username);

    /** @param passwordHash as produced by the configured {@link PasswordEncoder} */
    record Account(Principal principal, String passwordHash) {}
}
