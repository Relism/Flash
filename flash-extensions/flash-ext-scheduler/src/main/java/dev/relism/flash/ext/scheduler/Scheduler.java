package dev.relism.flash.ext.scheduler;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs background jobs on an interval or a cron expression.
 *
 * <pre>{@code
 * Scheduler jobs = ctx.require(Scheduler.class);
 *
 * jobs.every(Duration.ofMinutes(5), reports::refresh);
 * jobs.cron("0 0 3 * * *", archive::sweep);
 * }</pre>
 *
 * <p>One platform thread keeps time; every job body runs on a virtual thread, so a slow job
 * blocks nothing but itself.
 *
 * <p><b>Overlapping runs are skipped, always.</b> Not an option — two copies of the same job
 * running at once is a bug in every case anyone has needed so far, and a flag would only let it
 * be configured wrongly. A skipped run is logged at WARN with how long the previous one has been
 * going.
 */
@Slf4j
public final class Scheduler {

    private final ScheduledExecutorService clock;
    private final ScheduledExecutorService workers;
    private final List<Job> jobs = new CopyOnWriteArrayList<>();
    private volatile boolean stopped;

    Scheduler() {
        ThreadFactory timing = runnable -> {
            Thread thread = new Thread(runnable, "flash-scheduler");
            thread.setDaemon(true);
            return thread;
        };
        this.clock = Executors.newSingleThreadScheduledExecutor(timing);
        this.workers = Executors.newScheduledThreadPool(0, Thread.ofVirtual().factory());
    }

    /** Runs {@code work} every {@code interval}, starting one interval from now. */
    public void every(Duration interval, Runnable work) {
        every(callerName(work), interval, work);
    }

    /** Runs {@code work} every {@code interval} under an explicit name, used in logs. */
    public void every(String name, Duration interval, Runnable work) {
        if (interval.isZero() || interval.isNegative())
            throw new IllegalArgumentException("Interval must be positive for job '" + name + "'");
        Job job = new Job(name, work);
        jobs.add(job);
        long millis = interval.toMillis();
        clock.scheduleAtFixedRate(() -> dispatch(job), millis, millis, TimeUnit.MILLISECONDS);
        log.info("Scheduled '{}' every {}", name, interval);
    }

    /** Runs {@code work} on a cron schedule. The expression is validated now, not at fire time. */
    public void cron(String expression, Runnable work) {
        cron(callerName(work), expression, work);
    }

    /** Runs {@code work} on a cron schedule under an explicit name. */
    public void cron(String name, String expression, Runnable work) {
        Cron cron = Cron.parse(expression);
        Job job = new Job(name, work);
        jobs.add(job);
        scheduleNext(job, cron);
        log.info("Scheduled '{}' at cron [{}]", name, expression);
    }

    /** Job names in registration order — for a health or ops endpoint. */
    public List<String> jobNames() {
        return jobs.stream().map(job -> job.name).toList();
    }

    private void scheduleNext(Job job, Cron cron) {
        if (stopped) return;
        ZonedDateTime now = ZonedDateTime.now();
        long delay = Math.max(1, Duration.between(now, cron.nextAfter(now)).toMillis());
        clock.schedule(() -> {
            dispatch(job);
            scheduleNext(job, cron);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void dispatch(Job job) {
        if (stopped) return;
        if (!job.running.compareAndSet(false, true)) {
            log.warn("Skipping '{}': previous run started {} ago and is still going",
                    job.name, Duration.ofNanos(System.nanoTime() - job.startedAtNanos));
            return;
        }
        job.startedAtNanos = System.nanoTime();
        workers.execute(() -> {
            try {
                job.work.run();
            } catch (RuntimeException failure) {
                // A throwing job must not kill its schedule the way a raw scheduleAtFixedRate would.
                log.error("Job '{}' failed", job.name, failure);
            } finally {
                job.running.set(false);
            }
        });
    }

    /** Called by {@link SchedulerExtension} through {@code FlashContext.onClose}. */
    void shutdown(Duration grace) {
        stopped = true;
        clock.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Scheduler jobs still running after {} — forcing shutdown", grace);
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    /** Method references carry a usable name; lambdas do not, so those get a positional one. */
    private String callerName(Runnable work) {
        String simple = work.getClass().getSimpleName();
        return simple.isEmpty() || simple.contains("$$Lambda") ? "job-" + (jobs.size() + 1) : simple;
    }

    private static final class Job {
        final String name;
        final Runnable work;
        final AtomicBoolean running = new AtomicBoolean();
        volatile long startedAtNanos;

        Job(String name, Runnable work) {
            this.name = name;
            this.work = work;
        }
    }
}
