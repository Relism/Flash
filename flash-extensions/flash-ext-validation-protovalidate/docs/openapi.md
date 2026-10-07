# OpenAPI

With `flash-ext-openapi` on the classpath, the rules of every message type used by a route are added
to the component schemas that `flash-ext-protobuf-java` describes. They are read from the message
descriptors.

| Rule | Schema |
|---|---|
| `gte`, `lte` on numbers | `minimum`, `maximum` |
| `gt`, `lt` on numbers | `minimum`, `maximum` with the exclusive flag |
| `min_len`, `max_len`, `len` on strings | `minLength`, `maxLength` |
| `pattern` | `pattern` |
| `min_items`, `max_items` | `minItems`, `maxItems` |
| `unique` | `uniqueItems` |
| `min_pairs`, `max_pairs` | `minProperties`, `maxProperties` |
| `const`, `in` | `enum` |
| `not_in` | `not` with `enum` |
| `email`, `hostname`, `ip`, `ipv4`, `ipv6`, `uri`, `uuid` and the other format rules | `format` |
| `required` | listed under `required` |
| `example` | `example` |

Rules that OpenAPI has no keyword for (`within`, `prefix`, `suffix`, `contains`, CEL expressions,
oneof rules and the like) are written as sentences in the property's `description`, for example
`Must start with "ab".`.
