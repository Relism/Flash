# Cron expressions

`Cron.parse(expression)` accepts five fields, `min hour day-of-month month day-of-week`, or six with a
leading seconds field. Without a seconds field the job fires at second 0.

| Field | Values | Names |
|---|---|---|
| second (six-field form only) | 0-59 | |
| minute | 0-59 | |
| hour | 0-23 | |
| day of month | 1-31 | |
| month | 1-12 | `JAN` to `DEC` |
| day of week | 0-6, where 0 and 7 are Sunday | `SUN` to `SAT` |

Each field accepts `*` (or `?`), a value, a range `a-b`, a step `*/n` or `a/n` (from `a` to the end of
the field), and comma-separated lists of these.

```
0 0 3 * * *       03:00:00 every day
*/15 * * * *      every 15 minutes
0 9 * * MON-FRI   09:00 on weekdays
0 0 1 JAN *       midnight on 1 January
```

When both day-of-month and day-of-week are restricted, a day matches if it satisfies either, as in
standard cron.

## Errors

`IllegalArgumentException` for the wrong number of fields, a value out of range, an inverted range, a
non-positive step, a field that matches nothing, or a token that is not a number or name.

## Next fire time

`cron.nextAfter(zonedDateTime)` returns the first instant strictly after the argument that matches,
advancing by the largest unit that cannot match rather than second by second. It gives up after a
bounded number of steps and throws `IllegalStateException`, for example for `0 0 30 2 *`.
