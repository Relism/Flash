package dev.relism.flash.ext.webdav.internal;

/** A storage that keeps its own {@link Space}, instead of the one the handler keeps in memory. */
public interface OwnSpace {

    /** Its space, or {@code null} when the handler keeps it. */
    Space space();
}
