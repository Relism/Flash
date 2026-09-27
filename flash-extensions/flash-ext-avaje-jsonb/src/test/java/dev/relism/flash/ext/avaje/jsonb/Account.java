package dev.relism.flash.ext.avaje.jsonb;

import io.avaje.jsonb.Json;

/** Public and top-level: the generated adapter lives in another package and has to see it. */
@Json
public record Account(@Json.Property("user_name") String name, @Json.Ignore String secret) {}
