package dev.relism.flash.ext.avaje.jsonb;

import io.avaje.jsonb.Json;

/** A boolean read through {@code isActive()} and written under another name. */
@Json
public class Flag {
    @Json.Property("enabled")
    private boolean active;

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
