package dev.relism.flash.ext.validation.avaje;

import io.avaje.validation.constraints.Valid;
import jakarta.validation.constraints.NotBlank;

/** Avaje's {@code @Valid} asks the processor for an adapter; the rules stay jakarta's. */
@Valid
public record Address(@NotBlank String street) {}
