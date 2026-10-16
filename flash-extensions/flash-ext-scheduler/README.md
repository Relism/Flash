# flash-ext-scheduler

Background jobs on an interval or a cron expression, stopped with the app.

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-scheduler</artifactId>
</dependency>
```

```java
Scheduler jobs = new Scheduler();
app.install(jobs);
jobs.every(Duration.ofMinutes(5), reports::refresh);
jobs.cron("0 0 3 * * *", archive::sweep);
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | Intervals, cron, overlap, shutdown |
| [docs/cron.md](docs/cron.md) | Cron expression syntax |
