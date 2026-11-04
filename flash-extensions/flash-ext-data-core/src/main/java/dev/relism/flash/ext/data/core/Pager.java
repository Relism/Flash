package dev.relism.flash.ext.data.core;

import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Request;
import dev.relism.fpr.core.ByteView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * How the pages of one type are asked for: which of its fields a client may sort by, under which
 * names, and how the position after a page travels to the next request. Built once per type, at
 * boot, and shared; {@link #read} turns {@code ?size=&sort=&after=} into a {@link PageRequest}.
 *
 * <pre>{@code
 * private static final Pager<User> PAGER = Pager.of(User.class);
 * ...
 * Page<User> page = users.findPage(PAGER.read(request));
 * }</pre>
 *
 * <p>A handler that takes a {@code PageRequest<User>} through flash-ext-serde gets one built for it,
 * with the field names of its codec.
 */
public final class Pager<T> {

    /** Items in a page when the request does not say. */
    public static final int DEFAULT_SIZE = 20;
    /** The most a request may ask for. */
    public static final int MAX_SIZE = 100;

    private static final byte VERSION = 1;
    private static final Base64.Encoder TOKEN = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKENS = Base64.getUrlDecoder();

    final Class<T> type;
    /** The id first, then every {@link Sortable} field in declaration order. */
    final Key[] keys;
    private final PageRequest<T> first;

    private Pager(Class<T> type, Function<Field, String> names) {
        this.type = type;
        this.keys = keys(type, names);
        this.first = new PageRequest<>(this, DEFAULT_SIZE, 0, null);
    }

    /** The pager of {@code type}, its fields named as in Java. */
    public static <T> Pager<T> of(Class<T> type) {
        return of(type, Field::getName);
    }

    /** The pager of {@code type}, its fields named by {@code names}: a codec's wire names. */
    public static <T> Pager<T> of(Class<T> type, Function<Field, String> names) {
        return new Pager<>(type, names);
    }

    /**
     * The page {@code request} asks for. Absent or empty parameters take their default; anything
     * else that is not a size from 1 to {@link #MAX_SIZE}, a sortable name, or a token this pager
     * issued for the same sort is a 400.
     */
    public PageRequest<T> read(Request request) {
        ByteView query = request.rawQuery();
        if (query == null || query.length() == 0) return first;
        int size = DEFAULT_SIZE, sort = 0;
        boolean sized = false, sorted = false, after = false;
        int length = query.length();
        for (int i = 0; i < length; i++) {
            int keyStart = i;
            while (i < length && query.byteAt(i) != '=' && query.byteAt(i) != '&') i++;
            if (i == length || query.byteAt(i) == '&') continue;
            int keyEnd = i++, valueStart = i;
            while (i < length && query.byteAt(i) != '&') i++;
            if (valueStart == i) continue;
            if (!sized && named(query, keyStart, keyEnd, "size")) {
                sized = true;
                size = size(query, valueStart, i);
            } else if (!sorted && named(query, keyStart, keyEnd, "sort")) {
                sorted = true;
                sort = sort(request, query, valueStart, i);
            } else if (!after) {
                after = named(query, keyStart, keyEnd, "after");
            }
        }
        if (!after) return size == DEFAULT_SIZE && sort == 0 ? first : new PageRequest<>(this, size, sort, null);
        return new PageRequest<>(this, size, sort, after(request, sort));
    }

    /** What {@code sort} accepts, in order: {@code id}, {@code -id}, then each sortable field. */
    public List<String> sorts() {
        List<String> sorts = new ArrayList<>(keys.length * 2);
        for (Key key : keys) {
            sorts.add(key.wire);
            sorts.add("-" + key.wire);
        }
        return sorts;
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    private static boolean named(ByteView query, int start, int end, String name) {
        if (end - start != name.length()) return false;
        for (int i = 0; i < name.length(); i++) if (query.byteAt(start + i) != name.charAt(i)) return false;
        return true;
    }

    private static int size(ByteView query, int start, int end) {
        int size = 0;
        for (int i = start; i < end; i++) {
            byte b = query.byteAt(i);
            if (b < '0' || b > '9' || end - start > 3) throw invalidSize();
            size = size * 10 + (b - '0');
        }
        if (size < 1 || size > MAX_SIZE) throw invalidSize();
        return size;
    }

    private static HttpException invalidSize() {
        return HttpException.badRequest("size must be a number from 1 to " + MAX_SIZE);
    }

    /** The key and direction named by the value, matched on the request's own bytes. */
    private int sort(Request request, ByteView query, int start, int end) {
        if (escaped(query, start, end)) return sort(value(request, "sort"));
        boolean desc = query.byteAt(start) == '-';
        int from = desc ? start + 1 : start;
        for (int k = 0; k < keys.length; k++) {
            byte[] wire = keys[k].wireBytes;
            if (wire.length != end - from) continue;
            int i = 0;
            while (i < wire.length && wire[i] == query.byteAt(from + i)) i++;
            if (i == wire.length) return k << 1 | (desc ? 1 : 0);
        }
        throw unknownSort();
    }

    private int sort(String value) {
        boolean desc = value.startsWith("-");
        String name = desc ? value.substring(1) : value;
        for (int k = 0; k < keys.length; k++) if (keys[k].wire.equals(name)) return k << 1 | (desc ? 1 : 0);
        throw unknownSort();
    }

    private HttpException unknownSort() {
        List<String> names = new ArrayList<>(keys.length);
        for (Key key : keys) names.add(key.wire);
        return HttpException.badRequest("sort must be one of " + String.join(", ", names) + ", with - to reverse it");
    }

    private Object[] after(Request request, int sort) {
        int version, issuedFor;
        DataInputStream in;
        try {
            in = new DataInputStream(new ByteArrayInputStream(TOKENS.decode(value(request, "after"))));
            version = in.readByte();
            issuedFor = in.readShort();
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalidAfter();
        }
        if (version != VERSION) throw invalidAfter();
        if (issuedFor != sort) throw HttpException.badRequest("after belongs to a page with another sort");
        Key key = keys[sort >> 1];
        try {
            Object value = key == keys[0] || key.nullable && in.readBoolean() ? null : key.read(in);
            Object id = keys[0].read(in);
            if (in.available() != 0) throw invalidAfter();
            return new Object[] {key == keys[0] ? id : value, id};
        } catch (IOException | RuntimeException malformed) {   // a DateTimeParseException is not an IllegalArgumentException
            throw malformed instanceof HttpException http ? http : invalidAfter();
        }
    }

    private static HttpException invalidAfter() {
        return HttpException.badRequest("after is not a value this API issued");
    }

    /** The first value of {@code name} that is not empty, decoded: what the scan above counted. */
    private static String value(Request request, String name) {
        for (String value : request.queries(name)) if (!value.isEmpty()) return value;
        return "";
    }

    private static boolean escaped(ByteView query, int start, int end) {
        for (int i = start; i < end; i++) {
            byte b = query.byteAt(i);
            if (b == '%' || b == '+') return true;
        }
        return false;
    }

    // ── The token after a page ────────────────────────────────────────────────

    /** Where the page that ends with {@code last} stops: its sort value and its id, for the next request. */
    String token(int sort, T last) {
        Key key = keys[sort >> 1];
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            out.writeShort(sort);
            if (key != keys[0]) {
                Object value = key.get(last);
                if (key.nullable) out.writeBoolean(value == null);
                if (value != null) out.writeUTF(value.toString());
            }
            out.writeUTF(keys[0].get(last).toString());
            return TOKEN.encodeToString(bytes.toByteArray());
        } catch (IOException tooLong) {   // writeUTF holds 65535 bytes
            throw new IllegalStateException("a page key of " + type.getName() + " is too long for a token", tooLong);
        }
    }

    // ── Keys ──────────────────────────────────────────────────────────────────

    /** One field the pages can be sorted by: its Java name, its wire name, and how its text form is read back. */
    static final class Key {
        final String property;
        final String wire;
        final byte[] wireBytes;
        final boolean nullable;
        private final Field field;
        private final Function<String, Object> parse;

        Key(Field field, String wire) {
            this.field = field;
            this.property = field.getName();
            this.wire = wire;
            this.wireBytes = wire.getBytes(StandardCharsets.UTF_8);
            this.nullable = !field.getType().isPrimitive();
            this.parse = PARSE.get(field.getType());
            if (parse == null) throw new IllegalStateException(field.getDeclaringClass().getName() + "." + field.getName()
                    + " is a " + field.getType().getSimpleName() + ": a page key is a string, a number, a boolean, a UUID or a java.time value");
            field.setAccessible(true);
        }

        Object get(Object entity) {
            try {
                return field.get(entity);
            } catch (IllegalAccessException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        Object read(DataInputStream in) throws IOException {
            return parse.apply(in.readUTF());
        }
    }

    /** The types a key may have, each read back from its {@code toString}. */
    private static final Map<Class<?>, Function<String, Object>> PARSE = Map.ofEntries(
            Map.entry(String.class, text -> text),
            Map.entry(boolean.class, Boolean::valueOf), Map.entry(Boolean.class, Boolean::valueOf),
            Map.entry(byte.class, Byte::valueOf), Map.entry(Byte.class, Byte::valueOf),
            Map.entry(short.class, Short::valueOf), Map.entry(Short.class, Short::valueOf),
            Map.entry(int.class, Integer::valueOf), Map.entry(Integer.class, Integer::valueOf),
            Map.entry(long.class, Long::valueOf), Map.entry(Long.class, Long::valueOf),
            Map.entry(float.class, Float::valueOf), Map.entry(Float.class, Float::valueOf),
            Map.entry(double.class, Double::valueOf), Map.entry(Double.class, Double::valueOf),
            Map.entry(BigDecimal.class, BigDecimal::new), Map.entry(BigInteger.class, BigInteger::new),
            Map.entry(UUID.class, UUID::fromString), Map.entry(Instant.class, Instant::parse),
            Map.entry(LocalDate.class, LocalDate::parse), Map.entry(LocalTime.class, LocalTime::parse),
            Map.entry(LocalDateTime.class, LocalDateTime::parse), Map.entry(OffsetDateTime.class, OffsetDateTime::parse));

    private static Key[] keys(Class<?> type, Function<Field, String> names) {
        Field id = null;
        List<Field> sortable = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            List<Field> declared = new ArrayList<>();
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    if (field.isAnnotationPresent(Sortable.class))
                        throw new IllegalStateException(type.getName() + "." + field.getName() + " is static: @Sortable is for fields of the entity");
                    continue;
                }
                if (identifies(field) && (id == null || !annotatedId(id))) id = field;
                if (field.isAnnotationPresent(Sortable.class)) declared.add(field);
            }
            sortable.addAll(0, declared);   // a superclass's fields come first
        }
        if (id == null)
            throw new IllegalStateException(type.getName() + " has no id to page by: a field named id, or one annotated @Id");
        List<Key> keys = new ArrayList<>();
        keys.add(new Key(id, names.apply(id)));
        for (Field field : sortable) if (field != id) keys.add(new Key(field, names.apply(field)));
        Set<String> seen = new HashSet<>();
        for (Key key : keys)
            if (!seen.add(key.wire))
                throw new IllegalStateException(type.getName() + " has two sortable fields named " + key.wire);
        return keys.toArray(Key[]::new);
    }

    /** {@code @Id} of JPA wins over a field called {@code id}; the annotation is matched by name, so JPA stays optional. */
    private static boolean identifies(Field field) {
        return annotatedId(field) || field.getName().equals("id");
    }

    private static boolean annotatedId(Field field) {
        for (Annotation annotation : field.getAnnotations())
            if (annotation.annotationType().getName().equals("jakarta.persistence.Id")) return true;
        return false;
    }
}
