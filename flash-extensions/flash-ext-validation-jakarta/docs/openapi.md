# OpenAPI

When `flash-ext-openapi` is on the classpath, these constraints are written into the property schema.
Constraints with no schema counterpart (temporal bounds, digit counts, assertions) are enforced but
not documented.

| Constraint | Schema |
|---|---|
| `@NotNull` | property is required |
| `@NotBlank` | required, `minLength: 1` |
| `@NotEmpty` | required, and minimum of 1 as `minLength`, `minItems` or `minProperties` by the property type |
| `@Size(min, max)` | `minLength`/`maxLength`, `minItems`/`maxItems` or `minProperties`/`maxProperties` by type. Defaults are omitted |
| `@Min`, `@Max` | `minimum`, `maximum` |
| `@DecimalMin`, `@DecimalMax` | `minimum`, `maximum`, plus `exclusiveMinimum` or `exclusiveMaximum` when not inclusive |
| `@Positive` | `minimum: 0`, `exclusiveMinimum: true` |
| `@PositiveOrZero` | `minimum: 0` |
| `@Negative` | `maximum: 0`, `exclusiveMaximum: true` |
| `@NegativeOrZero` | `maximum: 0` |
| `@Email` | `format: email` |
| `@Pattern(regexp, message)` | `pattern`, and the message is added to the description |
