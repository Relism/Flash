package dev.relism.flash.ext.scheduler;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

    private static FlashApp app() {
        return FlashApp.create(FlashConfiguration.builder()
                .port(0).host("127.0.0.1").shutdownDrainTimeoutMs(250).build());
    }

    @Test
    void intervalJobsRunRepeatedly() throws Exception {
        FlashApp app = app().install(new SchedulerExtension());
        CountDownLatch ran = new CountDownLatch(3);
        try {
            app.start();
            app.ctx().require(Scheduler.class).every(Duration.ofMillis(30), ran::countDown);

            assertTrue(ran.await(3, TimeUnit.SECONDS), "interval job should have run three times");
        } finally {
            app.stop().join();
        }
    }

    @Test
    void overlappingRunsAreSkipped() throws Exception {
        FlashApp app = app().install(new SchedulerExtension());
        AtomicInteger started = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        try {
            app.start();
            app.ctx().require(Scheduler.class).every(Duration.ofMillis(20), () -> {
                started.incrementAndGet();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });

            Thread.sleep(300);
            assertEquals(1, started.get(), "a job still running must not be started again");
        } finally {
            release.countDown();
            app.stop().join();
        }
    }

    @Test
    void aThrowingJobKeepsItsSchedule() throws Exception {
        FlashApp app = app().install(new SchedulerExtension());
        CountDownLatch ran = new CountDownLatch(3);
        try {
            app.start();
            app.ctx().require(Scheduler.class).every(Duration.ofMillis(30), () -> {
                ran.countDown();
                throw new IllegalStateException("boom");
            });

            assertTrue(ran.await(3, TimeUnit.SECONDS), "a throwing job must not cancel its own schedule");
        } finally {
            app.stop().join();
        }
    }

    /** The reason SchedulerExtension needs FlashContext.onClose: a job must not outlive its app. */
    @Test
    void stoppingTheAppStopsTheScheduler() throws Exception {
        FlashApp app = app().install(new SchedulerExtension());
        AtomicInteger runs = new AtomicInteger();
        app.start();
        app.ctx().require(Scheduler.class).every(Duration.ofMillis(20), runs::incrementAndGet);
        Thread.sleep(120);
        assertTrue(runs.get() > 0, "job should have run while the app was up");

        app.stop().join();
        int afterStop = runs.get();
        Thread.sleep(200);

        assertEquals(afterStop, runs.get(), "no job may run after the app stopped");
    }

    @Test
    void badCronFailsWhenRegisteredNotWhenItWouldFire() throws Exception {
        FlashApp app = app().install(new SchedulerExtension());
        try {
            app.start();
            Scheduler scheduler = app.ctx().require(Scheduler.class);

            assertThrows(IllegalArgumentException.class, () -> scheduler.cron("not a cron", () -> {}));
        } finally {
            app.stop().join();
        }
    }

    @Test
    void jobNamesAreReportedForOps() throws Exception {
        FlashApp app = app().install(new SchedulerExtension());
        try {
            app.start();
            Scheduler scheduler = app.ctx().require(Scheduler.class);
            scheduler.every("refresh-reports", Duration.ofMinutes(5), () -> {});
            scheduler.cron("nightly-archive", "0 0 3 * * *", () -> {});

            assertEquals(java.util.List.of("refresh-reports", "nightly-archive"), scheduler.jobNames());
        } finally {
            app.stop().join();
        }
    }
}
