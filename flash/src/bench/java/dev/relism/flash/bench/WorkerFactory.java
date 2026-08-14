package dev.relism.flash.bench;

/** Builds one worker's {@link WorkUnit} — its own connection/client, isolated per virtual thread. */
@FunctionalInterface
interface WorkerFactory {
  WorkUnit create() throws Exception;
}
