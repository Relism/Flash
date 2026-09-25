# flash-ext-validation-jakarta

Jakarta Validation, as Hibernate Validator implements it.

```java
app.install(new JsonExtension())
   .install(new JakartaValidationExtension());
```

Nothing is asked of the build. Every constraint of the specification, cascading through `@Valid`,
validation groups, custom constraints and localized messages — all of it the engine's:

```java
public record Address(@NotBlank String street) {}

public record CreateUser(@NotBlank @Size(max = 8) String name,
                         @Email String email,
                         @Min(18) int age,
                         @Valid Address address) {}     // cascades
```

```java
@POST("/users")
public final class Create extends JsonHandler<CreateUser, UserView> { … }
```

A body that breaks a rule is a 422 whose message names the path — `address.street must not be
blank` — and the handler never sees it.

## What the document says

The rules of this engine become the schema's own bounds: `minLength`, `maxLength`, `minItems`,
`minimum`, `maximum`, `exclusiveMinimum`, `format: email`, `pattern`, and the required list. A
`@Pattern` also puts its own message in the property's description, because a regular expression has
no keyword a reader understands.

What has no schema counterpart — a temporal bound, `@Digits`, an assertion — is enforced without
being documented.

## Notes

- `new JakartaValidationExtension(validator)` takes a validator configured by hand: a message
  interpolator, a clock, a parameter name provider.
- Brings `expressly`, the Jakarta EL implementation the engine wants for message interpolation.
  Without it the defaults of `@DecimalMin` and `@DecimalMax` come back un-interpolated and the
  engine is not specification compliant.
- Two validation engines in one application is a boot failure: see
  [`flash-ext-validation-core`](../flash-ext-validation-core).
