package dev.relism.flash.ext.scheduler;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CronTest {

    private static ZonedDateTime at(String isoLocal) {
        return ZonedDateTime.parse(isoLocal + "Z", java.time.format.DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    private static String next(String expression, String from) {
        return Cron.parse(expression).nextAfter(at(from)).withZoneSameInstant(ZoneOffset.UTC).toString();
    }

    @Test
    void dailyAtAFixedTime() {
        assertEquals("2026-01-01T03:00Z", next("0 0 3 * * *", "2026-01-01T02:59:59"));
        assertEquals("2026-01-02T03:00Z", next("0 0 3 * * *", "2026-01-01T03:00:00"));
    }

    @Test
    void fiveFieldExpressionsFireOnTheMinute() {
        assertEquals("2026-01-01T03:00Z", next("0 3 * * *", "2026-01-01T02:59:00"));
    }

    @Test
    void stepsAndLists() {
        assertEquals("2026-01-01T00:15Z", next("*/15 * * * *", "2026-01-01T00:01:00"));
        assertEquals("2026-01-01T00:30Z", next("0,30 * * * *", "2026-01-01T00:15:00"));
    }

    @Test
    void namedMonthsAndWeekdays() {
        assertEquals("2026-01-05T09:00Z", next("0 9 * * MON-FRI", "2026-01-03T10:00:00"));
        assertEquals("2026-03-01T00:00Z", next("0 0 1 MAR *", "2026-01-31T00:00:00"));
    }

    @Test
    void bothSundayEncodingsWork() {
        assertEquals(next("0 0 * * 0", "2026-01-01T00:00:00"), next("0 0 * * 7", "2026-01-01T00:00:00"));
    }

    @Test
    void restrictedDayFieldsAreAUnionNotAnIntersection() {
        // "1st of the month, or any Monday" — standard cron semantics.
        assertEquals("2026-01-05T00:00Z", next("0 0 1 * MON", "2026-01-02T00:00:00"));
    }

    @Test
    void leapDayResolvesWithinTheGuard() {
        assertEquals("2028-02-29T00:00Z", next("0 0 29 FEB *", "2026-01-01T00:00:00"));
    }

    @Test
    void malformedExpressionsFailAtParseTimeNotFireTime() {
        for (String bad : List.of("* * *", "99 * * * *", "0 0 * * NOPE", "*/0 * * * *", "5-1 * * * *")) {
            assertThrows(IllegalArgumentException.class, () -> Cron.parse(bad), bad);
        }
    }
}
