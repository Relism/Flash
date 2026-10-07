# Using Protovalidate

```java
app.install(new ProtobufModule(), new ProtovalidateValidation());
app.install(new ProtobufModule(), new ProtovalidateValidation(myValidator));   // a build.buf.protovalidate.Validator
```

`ProtovalidateValidation` registers itself as `ProtobufValidation`, the engine the protobuf codec
asks for. It does not conflict with a Jakarta or Avaje `Validation` for JSON and XML in the same
app.

Installation requires a `Protobuf` codec: an `onStart` check throws if no `ProtobufModule` is
installed, because the engine has nothing to check without it.

## Behavior

`verify` ignores a value that is not a protobuf `Message`. For a message it runs the validator and,
when rules fail, throws a `ValidationException` with one `Violation` per rule violation:

| Part | Value |
|---|---|
| `path` | The field path: names joined by `.`, with `[index]` or `[key]` for repeated and map elements. `message` for a violation of the message itself |
| `message` | The rule's message, or `is invalid` when it has none |

A failure to evaluate a rule (for example a broken CEL expression) is an `IllegalStateException`,
not a `422`.
