package dev.relism.flash.ext.validation;

import dev.relism.flash.ext.jackson.Json;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;

/**
 * Installs request validation.
 *
 * <pre>{@code
 * FlashApp.create(8080)
 *     .install(new JacksonExtension())
 *     .install(new ValidationExtension())
 *     .scan("dev.example.api");
 * }</pre>
 *
 * <p>No configuration. There is nothing to tune: constraints come from the annotations already on
 * your types, failures come back as 422 through Flash's default exception handler because
 * {@link ValidationException} carries its own status, and the JSON codec is picked up if
 * {@code flash-ext-jackson} is installed.
 *
 * <p>Install order does not matter — Flash resolves the whole service graph before any handler
 * initialises.
 */
public final class ValidationExtension implements FlashExtension {

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        ctx.supply(Validation.class, Validation::new);

        // Resolved here rather than declared as a dependency: jackson is optional, and a declared
        // dependency would make it mandatory. By the time ready callbacks run the graph is
        // complete, so find() sees whatever was actually installed.
        ctx.onReady(() -> ctx.require(Validation.class).bindCodec(ctx.find(Json.class).orElse(null)));
    }
}
