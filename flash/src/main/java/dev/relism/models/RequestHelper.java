package dev.relism.models;

import dev.relism.exceptions.HttpException;

/**
 * Typed accessors for request parameters. All methods throw
 * {@link HttpException} (400) on missing or malformed input, so handlers
 * do not need to write per-field validation boilerplate.
 *
 * <pre>{@code
 * int  page = RequestHelper.queryInt(req, "page", 1, 100);
 * long id   = RequestHelper.paramLong(req, "id");
 * String q  = RequestHelper.queryRequired(req, "q");
 * }</pre>
 */
public final class RequestHelper {

    private RequestHelper() {}

    // ── Query parameters ──────────────────────────────────────────────────────

    /**
     * Returns the query param as an int, or {@code defaultValue} if absent.
     * Caps the result at {@code max}. Throws 400 on non-integer input.
     */
    public static int queryInt(Request req, String name, int defaultValue, int max) {
        String v = req.query(name);
        if (v == null || v.isEmpty()) return defaultValue;
        try {
            return Math.min(Integer.parseInt(v), max);
        } catch (NumberFormatException e) {
            throw HttpException.badRequest("Invalid integer for query param '" + name + "': " + v);
        }
    }

    /**
     * Returns the query param value. Throws 400 if absent or blank.
     */
    public static String queryRequired(Request req, String name) {
        String v = req.query(name);
        if (v == null || v.isBlank())
            throw HttpException.badRequest("Missing required query param: " + name);
        return v;
    }

    /**
     * Returns the query param, or {@code null} if absent.
     */
    public static String queryOptional(Request req, String name) {
        return req.query(name);
    }

    /**
     * Returns the query param as a long, or {@code defaultValue} if absent.
     * Throws 400 on non-long input.
     */
    public static long queryLong(Request req, String name, long defaultValue) {
        String v = req.query(name);
        if (v == null || v.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw HttpException.badRequest("Invalid long for query param '" + name + "': " + v);
        }
    }

    // ── Path parameters ───────────────────────────────────────────────────────

    /**
     * Returns the path parameter as a long. Throws 400 if absent or non-long.
     */
    public static long paramLong(Request req, String name) {
        String v = req.param(name);
        if (v == null)
            throw HttpException.badRequest("Missing path param: " + name);
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw HttpException.badRequest("Invalid long for path param '" + name + "': " + v);
        }
    }

    /**
     * Returns the path parameter as an int. Throws 400 if absent or non-int.
     */
    public static int paramInt(Request req, String name) {
        String v = req.param(name);
        if (v == null)
            throw HttpException.badRequest("Missing path param: " + name);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw HttpException.badRequest("Invalid int for path param '" + name + "': " + v);
        }
    }

    /**
     * Returns the path parameter as a String. Throws 400 if absent.
     */
    public static String paramRequired(Request req, String name) {
        String v = req.param(name);
        if (v == null)
            throw HttpException.badRequest("Missing path param: " + name);
        return v;
    }
}
