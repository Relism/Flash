package dev.relism.flash.ext.scheduler;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;

/**
 * A cron expression compiled to bitmasks.
 *
 * <p>Accepts five fields ({@code min hour dom mon dow}) or six with a leading seconds field.
 * Each is parsed once into a bitmask — a {@code long} for seconds and minutes, an {@code int} for
 * the rest — so matching a candidate instant is a shift and a mask rather than a parse, a list
 * walk or a set lookup.
 *
 * <pre>{@code
 * Cron.parse("0 0 3 * * *")    // 03:00:00 every day
 * Cron.parse("*}{@code /15 * * * *")     // every 15 minutes
 * Cron.parse("0 9 * * MON-FRI")  // 09:00 on weekdays
 * }</pre>
 */
public final class Cron {

    private final long seconds;
    private final long minutes;
    private final int hours;
    private final int daysOfMonth;
    private final int months;
    private final int daysOfWeek;
    private final boolean anyDayOfMonth;
    private final boolean anyDayOfWeek;
    private final String source;

    private Cron(long seconds, long minutes, int hours, int daysOfMonth, int months, int daysOfWeek,
                 boolean anyDayOfMonth, boolean anyDayOfWeek, String source) {
        this.seconds = seconds;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.anyDayOfMonth = anyDayOfMonth;
        this.anyDayOfWeek = anyDayOfWeek;
        this.source = source;
    }

    /** @throws IllegalArgumentException if the expression is malformed — at boot, not at fire time */
    public static Cron parse(String expression) {
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5 && fields.length != 6)
            throw new IllegalArgumentException(
                    "Cron needs 5 fields (min hour dom mon dow) or 6 with seconds, got " + fields.length
                            + ": " + expression);

        int offset = fields.length == 6 ? 1 : 0;
        long secondMask = offset == 1 ? mask(fields[0], 0, 59, null) : 1L;   // no seconds field: fire at :00
        return new Cron(
                secondMask,
                mask(fields[offset], 0, 59, null),
                (int) mask(fields[offset + 1], 0, 23, null),
                (int) mask(fields[offset + 2], 1, 31, null),
                (int) mask(fields[offset + 3], 1, 12, MONTHS),
                (int) mask(fields[offset + 4], 0, 6, DAYS),
                isWildcard(fields[offset + 2]),
                isWildcard(fields[offset + 4]),
                expression);
    }

    /**
     * The first instant strictly after {@code from} that matches.
     *
     * <p>Advances by the largest unit that cannot match rather than ticking second by second, so a
     * yearly expression resolves in a few dozen iterations instead of thirty million.
     */
    public ZonedDateTime nextAfter(ZonedDateTime from) {
        ZonedDateTime candidate = from.plusSeconds(1).withNano(0);
        // Four years covers the longest gap a valid expression can produce (29 February).
        for (int guard = 0; guard < 4 * 366 * 24; guard++) {
            if (!isSet(months, candidate.getMonthValue())) {
                candidate = candidate.plusMonths(1).withDayOfMonth(1).toLocalDate()
                        .atStartOfDay(candidate.getZone());
                continue;
            }
            if (!dayMatches(candidate)) {
                candidate = candidate.plusDays(1).toLocalDate().atStartOfDay(candidate.getZone());
                continue;
            }
            if (!isSet(hours, candidate.getHour())) {
                candidate = candidate.plusHours(1).withMinute(0).withSecond(0);
                continue;
            }
            if (!isSet(minutes, candidate.getMinute())) {
                candidate = candidate.plusMinutes(1).withSecond(0);
                continue;
            }
            if (!isSet(seconds, candidate.getSecond())) {
                candidate = candidate.plusSeconds(1);
                continue;
            }
            return candidate;
        }
        throw new IllegalStateException("Cron expression never fires: " + source);
    }

    @Override public String toString() { return source; }

    /**
     * Standard cron semantics: when both day fields are restricted the match is a union, not an
     * intersection — "1st of the month, or any Monday".
     */
    private boolean dayMatches(ZonedDateTime candidate) {
        boolean dom = isSet(daysOfMonth, candidate.getDayOfMonth());
        boolean dow = isSet(daysOfWeek, candidate.getDayOfWeek() == DayOfWeek.SUNDAY
                ? 0 : candidate.getDayOfWeek().getValue());
        if (anyDayOfMonth && anyDayOfWeek) return true;
        if (anyDayOfMonth) return dow;
        if (anyDayOfWeek) return dom;
        return dom || dow;
    }

    private static boolean isSet(long mask, int value) { return (mask & (1L << value)) != 0; }
    private static boolean isSet(int mask, int value)  { return (mask & (1 << value)) != 0; }

    private static boolean isWildcard(String field) { return "*".equals(field) || "?".equals(field); }

    private static final String[] MONTHS =
            {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    private static final String[] DAYS = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    /** Parses one field into a bitmask: {@code *}, {@code a}, {@code a-b}, {@code a/n}, and lists. */
    private static long mask(String field, int min, int max, String[] names) {
        long bits = 0;
        for (String part : field.split(",")) {
            int step = 1;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                step = Integer.parseInt(part.substring(slash + 1));
                if (step <= 0) throw new IllegalArgumentException("Cron step must be positive: " + field);
                part = part.substring(0, slash);
            }
            int from;
            int to;
            if (isWildcard(part)) {
                from = min;
                to = max;
            } else {
                int dash = part.indexOf('-');
                if (dash > 0) {
                    from = value(part.substring(0, dash), names, min, max, field);
                    to = value(part.substring(dash + 1), names, min, max, field);
                } else {
                    from = value(part, names, min, max, field);
                    to = slash >= 0 ? max : from;
                }
            }
            if (from > to) throw new IllegalArgumentException("Cron range is inverted: " + field);
            for (int v = from; v <= to; v += step) bits |= 1L << v;
        }
        if (bits == 0) throw new IllegalArgumentException("Cron field matches nothing: " + field);
        return bits;
    }

    private static int value(String token, String[] names, int min, int max, String field) {
        int parsed = -1;
        if (names != null) {
            String upper = token.toUpperCase();
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(upper)) { parsed = i + (names == MONTHS ? 1 : 0); break; }
            }
        }
        if (parsed < 0) {
            try {
                parsed = Integer.parseInt(token);
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException("Cron field is not a number: " + field, malformed);
            }
        }
        if (parsed == 7 && names == DAYS) parsed = 0;   // both 0 and 7 mean Sunday
        if (parsed < min || parsed > max)
            throw new IllegalArgumentException("Cron value " + parsed + " out of range " + min + "-" + max
                    + " in: " + field);
        return parsed;
    }
}
