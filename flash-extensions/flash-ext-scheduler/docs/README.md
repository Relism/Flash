# flash-ext-scheduler

Background jobs on an interval or a cron schedule. One platform thread keeps time, every job body
runs on a virtual thread, and the whole thing stops with the app.

## What it provides

| Component | Description |
|---|---|
| `Scheduler` | `every(interval, work)` and `cron(expression, work)` |
| `Cron` | A cron expression compiled to bitmasks; usable on its own |

## Dependency

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-scheduler</artifactId>
    <version>${flash.version}</version>
</dependency>
```

## Quick start

```java
FlashApp.create(8080)
    .install(new SchedulerExtension())
    .apply(new BlogApp())
    .start();
```

```java
// inside BlogApp.configure(app)
app.ctx().onReady(() -> {
    Scheduler jobs = app.ctx().require(Scheduler.class);

    jobs.every(Duration.ofMinutes(5), reports::refresh);
    jobs.cron("0 0 3 * * *", archive::sweep);
});
```

`onReady` is the right place: the service graph is resolved by then, so a job can close over the
services it needs.

Give a job an explicit name when the logs should be readable — a method reference names itself, a
lambda cannot:

```java
jobs.every("refresh-reports", Duration.ofMinutes(5), () -> reports.refresh());
jobs.cron("nightly-archive", "0 0 3 * * *", () -> archive.sweep());
```

`jobNames()` returns them in registration order, for an ops or health endpoint.

## Cron expressions

Five fields (`min hour dom mon dow`) or six with a leading seconds field.

| Expression | Fires |
|---|---|
| `0 0 3 * * *` | 03:00:00 daily |
| `*/15 * * * *` | every 15 minutes |
| `0 9 * * MON-FRI` | 09:00 on weekdays |
| `0 0 1 MAR *` | midnight on 1 March |
| `0,30 * * * *` | on the hour and the half hour |

Supports `*`, `?`, single values, `a-b` ranges, `a/n` steps, comma lists, and three-letter month
and day names. Both `0` and `7` mean Sunday.

Standard cron semantics for the two day fields: when **both** are restricted, a match is their
**union** — `0 0 1 * MON` means "the 1st, or any Monday", not "a Monday that is the 1st".

A malformed expression throws when you register the job, not the first time it would have fired.

## Overlapping runs are skipped

Not configurable. If a run is still going when the next is due, the next is skipped and a WARN
records how long the previous one has been running.

Two copies of the same job running at once is a bug in every case anyone has needed so far, and a
flag would only let it be configured wrongly. If you genuinely want concurrent runs, register the
job twice under different names.

## Failure

A throwing job is logged at ERROR and keeps its schedule. A raw `scheduleAtFixedRate` cancels the
task on the first exception, silently — a job that dies at 3am and is never heard from again is
the failure mode this avoids.

## Shutdown

The scheduler is registered with `FlashContext.onClose`, so `app.stop()` drains in-flight HTTP
requests first, then gives running jobs their grace period (10s by default) before forcing them
down. Nothing runs after the app has stopped.

```java
new SchedulerExtension(Duration.ofSeconds(30))   // longer grace for slow jobs
```

That is the only knob.

## Threading

`Scheduler` holds one daemon platform thread for timing and dispatches every job body to a virtual
thread. A slow job delays nothing but its own next run; it cannot occupy the timer or starve other
jobs.

## Performance

A cron expression is parsed **once**, into a bitmask per field — a `long` for seconds and minutes,
an `int` for the rest. Matching a candidate instant is a shift and a mask, not a parse or a set
lookup. Computing the next fire time advances by the largest unit that cannot match rather than
ticking second by second, so a yearly expression resolves in a few dozen iterations rather than
thirty million.

To be precise about where that matters: this runs once per fire, not once per request. The
allocation discipline elsewhere in Flash is about the request hot path, and a scheduler that
computes a `ZonedDateTime` a few times an hour is not on it. The bitmasks are here because parsing
a string on every candidate instant would be genuinely wasteful, not to shave an allocation.

## Known ceiling

**Schedules are per-instance.** Two replicas run every job twice. The fix is a distributed lock,
and it should not exist until there is a second replica — it is marked with a `ponytail:` comment
in `SchedulerExtension`.
