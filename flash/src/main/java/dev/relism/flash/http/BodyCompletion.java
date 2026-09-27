package dev.relism.flash.http;

/** Internal completion signal used to enforce request-trailer ordering. */
public interface BodyCompletion {
  boolean fullyRead();
}
