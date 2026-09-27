package dev.relism.flash.ext.scheduler;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

    private final Scheduler scheduler = new Scheduler(Duration.ofMillis(250));
    private final Server server = Server.start(App.create().install(scheduler),
            ServerConfig.builder().port(0).host("127.0.0.1").shutdownDrainTimeoutMs(250).build());

    @AfterEach
    void stop() {
        server.stop().join();
    }

    @Test
    void intervalJobsRunRepeatedly() throws Exception {
        CountDownLatch ran = new CountDownLatch(3);
        scheduler.every(Duration.ofMillis(30), ran::countDown);
        assertTrue(ran.await(3, TimeUnit.SECONDS), "interval job should have run three times");
    }

    @Test
    void jobsRunInParallel() throws Exception {
        CountDownLatch both = new CountDownLatch(2);
        Runnable meet = () -> {
            both.countDown();
            try { both.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        scheduler.every("a", Duration.ofMillis(20), meet);
        scheduler.every("b", Duration.ofMillis(20), meet);
        assertTrue(both.await(2, TimeUnit.SECONDS), "one job blocking must not hold back another");
    }

    @Test
    void overlappingRunsAreSkipped() throws Exception {
        AtomicInteger started = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        try {
            scheduler.every(Duration.ofMillis(20), () -> {
                started.incrementAndGet();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            Thread.sleep(300);
            assertEquals(1, started.get(), "a job still running must not be started again");
        } finally {
            release.countDown();
        }
    }

    @Test
    void aThrowingJobKeepsItsSchedule() throws Exception {
        CountDownLatch ran = new CountDownLatch(3);
        scheduler.every(Duration.ofMillis(30), () -> {
            ran.countDown();
            throw new IllegalStateException("boom");
        });
        assertTrue(ran.await(3, TimeUnit.SECONDS), "a throwing job must not cancel its own schedule");
    }

    @Test
    void stoppingTheAppStopsTheScheduler() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        scheduler.every(Duration.ofMillis(20), runs::incrementAndGet);
        Thread.sleep(120);
        assertTrue(runs.get() > 0, "job should have run while the app was up");

        server.stop().join();
        int afterStop = runs.get();
        Thread.sleep(200);
        assertEquals(afterStop, runs.get(), "no job may run after the app stopped");
    }

    @Test
    void badCronFailsWhenRegisteredNotWhenItWouldFire() {
        assertThrows(IllegalArgumentException.class, () -> scheduler.cron("not a cron", () -> {}));
    }

    @Test
    void jobNamesAreReportedForOps() {
        scheduler.every("refresh-reports", Duration.ofMinutes(5), () -> {});
        scheduler.cron("nightly-archive", "0 0 3 * * *", () -> {});
        assertEquals(List.of("refresh-reports", "nightly-archive"), scheduler.jobNames());
    }
}
