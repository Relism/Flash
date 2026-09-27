package dev.relism.flash.ext.security;

/** Where a browser may be sent back to after signing in. */
public final class Redirects {

    private Redirects() {}

    /**
     * {@code path} when it stays on this origin, {@code /} otherwise: an absolute URL, a
     * protocol-relative {@code //host} and a {@code /\host} a browser reads the same way are all
     * refused, so a sign-in link cannot send a user elsewhere.
     */
    public static String localPath(String path) {
        return path != null && path.startsWith("/") && !path.startsWith("//") && !path.startsWith("/\\") ? path : "/";
    }
}
