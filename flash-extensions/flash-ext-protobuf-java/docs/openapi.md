# OpenAPI

When `flash-ext-openapi` is on the classpath, `ProtobufModule` installs a contributor that describes
messages from their descriptors instead of reflecting on the generated class. Each message becomes a
schema under `components.schemas`, named by its full protobuf name, and is referenced elsewhere.

| Protobuf | Schema |
|---|---|
| `bool` | `boolean` |
| `int32`, `sint32`, `sfixed32` | `integer`, format `int32` |
| `int64`, `sint64`, `sfixed64` | `integer`, format `int64` |
| `uint32`, `uint64`, `fixed32`, `fixed64` | integer with `minimum: 0` |
| `float`, `double` | `number`, format `float` or `double` |
| `string` | `string` |
| `bytes` | `string`, format `byte` |
| enum | `string` with the value names as `enum` |
| `repeated` | `array` |
| `map<K, V>` | `object` with `additionalProperties` of the value type |
| nested message | `$ref` to its schema |

Well-known types are mapped to their JSON form: `Timestamp` to a `date-time` string, `Duration` to a
`duration` string, the wrapper types to nullable scalars, `Struct`, `ListValue` and `Value` to
objects, arrays and any value, `Any` to an object with `type_url` and `value`, and `Empty` to an empty
object.

Fields marked `deprecated` get `deprecated: true`, fields with a default get `default`, and
`required` fields are listed under `required`.
