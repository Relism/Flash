package dev.relism.flash.ext.ndjson;

import io.avaje.jsonb.Json;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** One NDJSON line, for both codecs: Avaje generates its adapter, Jackson reads the record. */
@Json
public record Row(@NotBlank String name, @Min(1) int quantity) {}
