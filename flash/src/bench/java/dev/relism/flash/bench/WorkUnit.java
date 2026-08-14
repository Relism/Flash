package dev.relism.flash.bench;

/** One worker's request loop body. {@link #close()} releases whatever {@link WorkerFactory} opened. */
interface WorkUnit extends AutoCloseable {
  void request() throws Exception;

  @Override
  default void close() throws Exception {}
}
