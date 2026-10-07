# Using the scheduler

`Scheduler` is a `Module` and `AutoCloseable`. Installing it registers it as a service, so the app
closes it on stop. Register jobs on the instance before or after starting the app.

```java
Scheduler jobs = new Scheduler(Duration.ofSeconds(30));      // grace period, default 10 s
app.install(jobs);
```

## Jobs

| Method | Runs |
|---|---|
| `every(interval, work)` | Every interval, the first run one interval from now. Fixed rate |
| `every(name, interval, work)` | The same with an explicit name |
| `cron(expression, work)` | On a cron schedule in the system time zone |
| `cron(name, expression, work)` | The same with a name |
| `cron(name, expression, zone, work)` | In a given `ZoneId` |
| `jobNames()` | Registered names, in order, for an ops endpoint |

A zero or negative interval throws `IllegalArgumentException`. A cron expression is parsed when you
register it, so a malformed one fails at registration, not when it fires.

The name appears in logs. A method reference gets its class's simple name, and a lambda gets a
positional name (`job-3`). Pass a name when it matters.

## Threads

One daemon platform thread keeps time. Each run executes on a virtual thread, so a slow job blocks
only itself.

## Overlap

A run that starts while the previous run of the same job is still going is skipped and logged at
WARN with how long the previous run has been going. This is not configurable. Different jobs run
concurrently.

## Failures

A `RuntimeException` from a job is logged at ERROR and does not cancel its schedule.

## Shutdown

`close()` stops scheduling, then waits up to the grace period for running jobs, then interrupts the
rest and logs a warning.

## Multiple instances

Schedules are per process. Two replicas run every job twice. Make jobs idempotent, or guard them
with a lock of your own.
