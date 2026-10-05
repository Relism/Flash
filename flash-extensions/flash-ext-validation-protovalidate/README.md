# flash-ext-validation-protovalidate

Protovalidate rules declared in `.proto` files, exposed through Flash's single `Validation`
contract. The validator is built once, uses Protovalidate's native rule evaluators by default, and
is called automatically by `flash-ext-protobuf-java` after a message is parsed.

```java
app.install(new ProtobufModule(), new ProtovalidateValidation());
```

Rules remain part of the protocol definition:

```proto
import "buf/validate/validate.proto";

message CreateUser {
  string email = 1 [(buf.validate.field).string.email = true];
  uint32 age = 2 [(buf.validate.field).uint32.lte = 150];
}
```

Configure the application protobuf build so `buf/validate/validate.proto` is available to
`protoc`; follow the current [Protovalidate Java documentation](https://github.com/bufbuild/protovalidate-java)
for Maven, Gradle or Buf setup. Broken rules are returned as Flash `422` validation failures with
their protobuf field paths. Rule compilation failures remain server errors rather than being
misreported as bad client input.

With `flash-ext-openapi` present, every rule is published, merged into the message's component schema. A rule
OpenAPI has a keyword for becomes that keyword; the rest is said in the property's `description`.

| Rule | In the document |
|---|---|
| `min_len`, `max_len`, `len`, `pattern` (string) | `minLength`, `maxLength`, `pattern` |
| `gt`, `gte`, `lt`, `lte` (numbers) | `minimum`, `maximum`, with `exclusiveMinimum` and `exclusiveMaximum` |
| `const`, `in`, `not_in` | `enum`, or `not: {enum}`; enums by name, bytes as base64, durations as `1.5s` |
| `email`, `hostname`, `uri`, `uuid`, `ipv4`, `ipv6` and the other well-known formats | `format` |
| `min_items`, `max_items`, `unique`; `items` | `minItems`, `maxItems`, `uniqueItems`; the item schema |
| `min_pairs`, `max_pairs`; `values` | `minProperties`, `maxProperties`; `additionalProperties` |
| `example` | `example` |
| `required` | the schema's `required` |
| `Any` `in` | `enum` on `type_url` |
| `prefix`, `suffix`, `contains`, `*_bytes`, bytes lengths, `gt`/`lt` on durations and timestamps, `within`, `lt_now`, `gt_now`, `finite`, `well_known_regex`, map `keys`, `cel`, `cel_expression` | a sentence in `description` |
| message `cel` and `oneof`, a `oneof`'s `required` | a sentence in the message's `description` |

Protovalidate rules are descriptor options, so this reads descriptors rather than Flash's annotation-based
`Constraints`.

Pass a configured validator only when the defaults are not enough:

```java
Validator validator = ValidatorFactory.newBuilder().withConfig(config).build();
app.install(new ProtovalidateValidation(validator));
```

It is the `ProtobufValidation` that `flash-ext-protobuf-java` asks for, so it sits beside Jakarta or Avaje in
one application: JSON and XML bodies go to that engine and protobuf bodies to this one. Another engine for
protobuf implements `ProtobufValidation` the same way. It refuses to boot without `ProtobufModule`, which is the
only thing that would ever call it.
