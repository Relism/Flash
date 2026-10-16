package dev.relism.flash.ext.ndjson;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.avaje.jsonb.Json;

/** A line whose wire name differs from its field, said once per codec. */
@Json
public record Named(@JsonProperty("item_name") @Json.Property("item_name") String name) {}
