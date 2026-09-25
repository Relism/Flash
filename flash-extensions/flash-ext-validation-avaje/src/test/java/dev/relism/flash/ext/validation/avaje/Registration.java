package dev.relism.flash.ext.validation.avaje;

import io.avaje.validation.constraints.Length;
import io.avaje.validation.constraints.Positive;
import io.avaje.validation.constraints.URI;
import io.avaje.validation.constraints.UUID;
import io.avaje.validation.constraints.Valid;
import jakarta.validation.constraints.NotBlank;

/** Jakarta's rules and this engine's own, side by side on one type. */
@Valid
public record Registration(@NotBlank String name,
                           @Length(max = 20) String nickname,
                           @URI String website,
                           @UUID String id,
                           @Positive int seats) {}
