package dev.relism.flash.ext.auth;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * A typed view over one request's claims — whatever the {@link CredentialSource} that
 * authenticated it produced. Obtained from {@link ClaimsHolder#current()}.
 *
 * <p>The accessors name claim <em>keys</em>, not a protocol: {@code sub} is RFC 7519, and
 * {@code email}, {@code name} and {@code preferred_username} are spelled the same way by every
 * token issuer worth integrating. A source that uses different keys exposes them through
 * {@link #claim(String)} or {@link #roles(String)}.
 *
 * <pre>{@code
 * app.get("/api/whoami", (req, res) -> {
 *     Claims c = ClaimsHolder.current();
 *     return Map.of("sub", c.sub(), "email", c.email(), "roles", c.roles("realm_access.roles"));
 * }, auth.protect());
 * }</pre>
 */
public final class Claims {

    private final Map<String, Object> claims;

    Claims(Map<String, Object> claims) {
        this.claims = claims;
    }

    // ── Common claims ─────────────────────────────────────────────────────────

    /** Subject identifier — the stable, unique id of the caller. */
    public String sub() { return str("sub"); }

    /** User's email address ({@code email} claim). */
    public String email() { return str("email"); }

    /** Human-readable username ({@code preferred_username} claim). */
    public String username() { return str("preferred_username"); }

    /** Full display name ({@code name} claim). */
    public String name() { return str("name"); }

    // ── Roles ────────────────────────────────────────────────────────────────

    /**
     * Extracts the roles list by traversing a dot-separated claim path.
     *
     * <p>Example paths:
     * <ul>
     *   <li>{@code "realm_access.roles"} — Keycloak realm roles</li>
     *   <li>{@code "resource_access.my-client.roles"} — Keycloak client roles</li>
     *   <li>{@code "groups"} — Authelia / generic IdPs</li>
     * </ul>
     *
     * @return list of role strings, or an empty list if the path doesn't exist
     */
    @SuppressWarnings("unchecked")
    public List<String> roles(String claimPath) {
        String[] parts   = claimPath.split("\\.");
        Object   current = claims;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> m)) return List.of();
            current = m.get(part);
        }
        if (current instanceof List<?> list)
            return list.stream().map(Object::toString).toList();
        return List.of();
    }

    /** Returns {@code true} if the user holds {@code role} at the given claim path. */
    public boolean hasRole(String claimPath, String role) {
        return roles(claimPath).contains(role);
    }

    // -- Scopes ---------------------------------------------------------------

    /**
     * Resolves scopes using the conventional fallback order:
     * {@code scope} then {@code scp}. Supports both space-separated string and list forms.
     */
    public List<String> scopes() {
        return scopes("scope,scp");
    }

    /**
     * Resolves scopes from comma-separated claim paths (example: {@code "scope,scp,permissions.scopes"}).
     */
    public List<String> scopes(String claimPaths) {
        List<String> out = new ArrayList<>();
        for (String[] path : splitClaimPaths(claimPaths)) {
            Object value = valueAtPath(path);
            if (value == null) continue;
            if (value instanceof String s) {
                appendDelimitedTokens(out, s);
                continue;
            }
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item == null) continue;
                    String token = item.toString().trim();
                    if (!token.isEmpty()) out.add(token);
                }
                continue;
            }
            String token = value.toString().trim();
            if (!token.isEmpty()) out.add(token);
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    /** Returns {@code true} if the user has {@code scope}, searching default claim paths {@code scope,scp}. */
    public boolean hasScope(String scope) {
        return hasScope("scope,scp", scope);
    }

    /** Returns {@code true} if the user has {@code scope} in any of {@code claimPaths}. */
    public boolean hasScope(String claimPaths, String scope) {
        if (scope == null || scope.isBlank()) return false;
        String target = scope.trim();
        for (String[] path : splitClaimPaths(claimPaths)) {
            Object value = valueAtPath(path);
            if (value == null) continue;
            if (value instanceof String s && containsDelimitedToken(s, target)) return true;
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item == null) continue;
                    if (target.equals(item.toString().trim())) return true;
                }
                continue;
            }
            if (target.equals(value.toString().trim())) return true;
        }
        return false;
    }

    // ── Arbitrary claim access ─────────────────────────────────────────────

    /**
     * Returns the value of any claim, cast to {@code T}.
     *
     * @throws ClassCastException if the stored value is not assignable to {@code type}
     */
    public <T> T claim(String key, Class<T> type) {
        return type.cast(claims.get(key));
    }

    /** Returns the raw claim value, or {@code null} if absent. */
    public Object claim(String key) { return claims.get(key); }

    /** Escape hatch — returns the full unmodified claims map. */
    public Map<String, Object> claims() { return claims; }

    // ── Internals ─────────────────────────────────────────────────────────

    private String str(String key) {
        Object v = claims.get(key);
        return v != null ? v.toString() : null;
    }

    private Object valueAtPath(String[] path) {
        Object current = claims;
        for (String part : path) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = m.get(part);
            if (current == null) return null;
        }
        return current;
    }

    private static String[][] splitClaimPaths(String claimPaths) {
        String source = (claimPaths == null || claimPaths.isBlank()) ? "scope,scp" : claimPaths;
        List<String[]> out = new ArrayList<>(4);
        int start = 0;
        int len = source.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || source.charAt(i) == ',') {
                String raw = source.substring(start, i).trim();
                if (!raw.isEmpty()) out.add(splitPath(raw));
                start = i + 1;
            }
        }
        return out.isEmpty() ? new String[][]{ splitPath("scope"), splitPath("scp") } : out.toArray(String[][]::new);
    }

    private static String[] splitPath(String path) {
        List<String> out = new ArrayList<>(4);
        int start = 0;
        int len = path.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || path.charAt(i) == '.') {
                String raw = path.substring(start, i).trim();
                if (!raw.isEmpty()) out.add(raw);
                start = i + 1;
            }
        }
        return out.isEmpty() ? new String[]{ path } : out.toArray(String[]::new);
    }

    private static void appendDelimitedTokens(List<String> target, String source) {
        int len = source.length();
        int i = 0;
        while (i < len) {
            while (i < len && isDelimiter(source.charAt(i))) i++;
            int start = i;
            while (i < len && !isDelimiter(source.charAt(i))) i++;
            if (i > start) target.add(source.substring(start, i));
        }
    }

    private static boolean containsDelimitedToken(String source, String token) {
        int len = source.length();
        int i = 0;
        while (i < len) {
            while (i < len && isDelimiter(source.charAt(i))) i++;
            int start = i;
            while (i < len && !isDelimiter(source.charAt(i))) i++;
            int end = i;
            if (end > start && end - start == token.length() && source.regionMatches(start, token, 0, token.length())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDelimiter(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',';
    }
}
