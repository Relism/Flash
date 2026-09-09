package dev.relism.flash.ext.scheduler;

import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;

import java.time.Duration;

/**
 * Installs the {@link Scheduler}.
 *
 * <pre>{@code
 * FlashApp.create(8080)
 *     .install(new SchedulerExtension())
 *     .apply(new BlogApp())
 *     .start();
 * }</pre>
 *
 * <p>No configuration beyond an optional shutdown grace period. The scheduler starts with the app
 * and stops with it: shutdown is registered through {@link FlashContext#onClose}, so
 * {@code app.stop()} drains in-flight requests first, then gives running jobs their grace period
 * before forcing them down. Without that a job would outlive the app that owns it.
 *
 * <p>ponytail: schedules are per-instance. Two replicas run every job twice. The fix is a
 * distributed lock, and it should not exist until there is a second replica.
 */
public final class SchedulerExtension implements FlashExtension {

    private static final Duration DEFAULT_GRACE = Duration.ofSeconds(10);

    private final Duration shutdownGrace;

    public SchedulerExtension() {
        this(DEFAULT_GRACE);
    }

    /** @param shutdownGrace how long {@code stop()} waits for running jobs before forcing them */
    public SchedulerExtension(Duration shutdownGrace) {
        this.shutdownGrace = shutdownGrace;
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        ctx.supply(Scheduler.class, services -> {
            Scheduler scheduler = new Scheduler();
            services.onClose(() -> scheduler.shutdown(shutdownGrace));
            return scheduler;
        });
    }
}
