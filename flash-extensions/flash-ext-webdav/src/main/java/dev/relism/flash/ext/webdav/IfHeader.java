package dev.relism.flash.ext.webdav;

import dev.relism.flash.http.HttpException;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code If} header of RFC 4918 §10.4: lists of conditions, each list untagged (about the
 * request URI) or tagged with the resource it is about. A condition is a state token
 * ({@code <urn:uuid:...>}) or an entity tag ({@code ["..."]}), either one negated by {@code Not}.
 */
record IfHeader(List<Clause> clauses) {

    /** One list: all its conditions hold, about {@code resource}, or the request URI when it is {@code null}. */
    record Clause(String resource, List<Condition> conditions) {}

    /** Exactly one of {@code token} and {@code etag} is set. */
    record Condition(boolean not, String token, String etag) {}

    static IfHeader parse(String header) {
        List<Clause> clauses = new ArrayList<>();
        Boolean tagged = null;
        String resource = null;
        int i = skip(header, 0);
        while (i < header.length()) {
            char c = header.charAt(i);
            if (c == '<') {
                if (Boolean.FALSE.equals(tagged)) throw malformed();
                tagged = true;
                int end = header.indexOf('>', i);
                if (end < 0) throw malformed();
                resource = header.substring(i + 1, end);
                i = skip(header, end + 1);
                if (i >= header.length() || header.charAt(i) != '(') throw malformed();
                continue;
            }
            if (c != '(') throw malformed();
            if (tagged == null) tagged = false;
            List<Condition> conditions = new ArrayList<>();
            i = skip(header, i + 1);
            while (i < header.length() && header.charAt(i) != ')') {
                boolean not = header.regionMatches(true, i, "Not", 0, 3);
                if (not) i = skip(header, i + 3);
                if (i >= header.length()) throw malformed();
                char open = header.charAt(i);
                char close = open == '<' ? '>' : open == '[' ? ']' : 0;
                int end = close == 0 ? -1 : header.indexOf(close, i);
                if (end < 0) throw malformed();
                String value = header.substring(i + 1, end);
                conditions.add(open == '<' ? new Condition(not, value, null) : new Condition(not, null, value));
                i = skip(header, end + 1);
            }
            if (i >= header.length() || conditions.isEmpty()) throw malformed();
            clauses.add(new Clause(tagged ? resource : null, List.copyOf(conditions)));
            i = skip(header, i + 1);
        }
        if (clauses.isEmpty()) throw malformed();
        return new IfHeader(List.copyOf(clauses));
    }

    /** The state tokens named without {@code Not}: the lock tokens the client submits. */
    List<String> tokens() {
        List<String> tokens = new ArrayList<>();
        for (Clause clause : clauses)
            for (Condition condition : clause.conditions)
                if (condition.token != null && !condition.not) tokens.add(condition.token);
        return tokens;
    }

    private static int skip(String header, int i) {
        while (i < header.length() && (header.charAt(i) == ' ' || header.charAt(i) == '\t')) i++;
        return i;
    }

    private static HttpException malformed() {
        return HttpException.badRequest("malformed If header");
    }
}
