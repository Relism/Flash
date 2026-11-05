# Using Protovalidate

```java
app.install(new ProtobufModule(), new ProtovalidateValidation());
app.install(new ProtobufModule(), new ProtovalidateValidation(myValidator));   // a build.buf.protovalidate.Validator
```

`ProtovalidateValidation` installs itself through `Validation.module` and checks only types that are
a protobuf `Message`, whichever codec decoded them. A Jakarta or Avaje engine has no constraint on a
generated message, so the two live in one app, each checking its own types.

## Behavior

`verify` ignores a value that is not a protobuf `Message`, and checks a collection element by
element. For a message it runs the validator and, when rules fail, throws a `ValidationException`
with one `Violation` per rule violation:

| Part | Value |
|---|---|
| `path` | The field path: names joined by `.`, with `[index]` or `[key]` for repeated and map elements. `message` for a violation of the message itself. In a collection, prefixed with the element's `[index].` |
| `message` | The rule's message, or `is invalid` when it has none |

A failure to evaluate a rule (for example a broken CEL expression) is an `IllegalStateException`,
not a `422`.
