# OpenAPI

When `flash-ext-openapi` is on the classpath, constraints are written into the property schema.

Jakarta constraints documented: `NotNull`, `NotBlank`, `NotEmpty`, `Size`, `Min`, `Max`, `Email`,
`Pattern`.

Avaje constraints documented: `NotNull`, `NotBlank` (also `maxLength` from its `max`), `NotEmpty`,
`Size`, `Length`, `Min`, `Max`, `DecimalMin`, `DecimalMax`, `Positive`, `PositiveOrZero`,
`Negative`, `NegativeOrZero`, `Email`, `URI`, `UUID`, `Pattern`.

| Constraint | Schema |
|---|---|
| not-null family | required; `NotBlank` and `NotEmpty` add a minimum of 1 |
| `Size`, `Length` | `minLength`/`maxLength`, `minItems`/`maxItems` or `minProperties`/`maxProperties` by the property type |
| `Min`, `Max`, `DecimalMin`, `DecimalMax` | `minimum`, `maximum`, with the exclusive flags when not inclusive |
| `Positive`, `Negative` | zero as an exclusive bound |
| `PositiveOrZero`, `NegativeOrZero` | zero as an inclusive bound |
| `Email`, `URI`, `UUID` | `format: email`, `uri`, `uuid` |
| `Pattern` | `pattern`, and the message is added to the description |

Other constraints are enforced and not documented.
