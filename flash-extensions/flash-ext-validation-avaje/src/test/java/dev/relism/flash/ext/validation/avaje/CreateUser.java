package dev.relism.flash.ext.validation.avaje;

import io.avaje.validation.constraints.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Valid
public record CreateUser(@NotBlank @Size(max = 8) String name,
                         @Email String email,
                         @Min(18) int age,
                         @jakarta.validation.Valid Address address) {}
