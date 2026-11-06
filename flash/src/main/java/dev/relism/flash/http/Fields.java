package dev.relism.flash.http;

/**
 * What RFC 9110 lets a response put on the wire: a field name is a token (5.1), a field value visible
 * ASCII, spaces and tabs (5.5). Anything else would end the field or the message early, a line break
 * in a value being a header of its own, or reach the wire cut to a byte it is not: U+010D is written
 * as a carriage return. Checked wherever text becomes a field, so no caller has to remember to.
 */
final class Fields {

    private Fields() {}

    /** @throws IllegalArgumentException naming the field and the position, never the value, which may be a secret */
    static void check(String name, String value) {
        if (name.isEmpty()) throw new IllegalArgumentException("Empty header name");
        for (int i = 0; i < name.length(); i++) if (!token(name.charAt(i))) throw invalid("name", name, i);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\t' || c > 0x7E) throw invalid("value", name, i);
        }
    }

    private static boolean token(char c) {
        return c > 0x20 && c < 0x7F && "\"(),/:;<=>?@[\\]{}".indexOf(c) < 0;
    }

    private static IllegalArgumentException invalid(String part, String name, int at) {
        return new IllegalArgumentException("The " + part + " of header " + name + " has a character a field cannot carry, at " + at);
    }
}
