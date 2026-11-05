# The validation contract

## Validation

```java
public interface Validation {
    void verify(Object value);
    default boolean checks(Class<?> type) { return true; }
    default void describe(Constraints constraints) {}
    static Module module(Validation engine) { ... }
}
```

`verify` checks a value against the rules its own type declares and throws `ValidationException`
when it breaks any. Which annotations exist and how they are enforced is the engine's business.

`checks` says whether the engine has rules for a type. It is asked once per decoded type, at boot,
and a type no engine checks is decoded with no call at all. An engine answers `true` for a type
whose values may be of a subclass with rules: an interface or an abstract class.

Engines install themselves through `Validation.module(engine)`, so several live in one app. Each
decoded type is checked by the one engine that checks it; two engines that both check a type fail
the boot at the handler that decodes it, naming both.

## ValidationException

```java
public final class ValidationException extends HttpException {
    public List<Violation> violations();
    public record Violation(String path, String message) {}
}
```

Status is `422`. The exception message joins every violation as `<path> <message>`, separated by
`; `, so the default error handler answers:

```json
{"error": "name must not be blank; age must be greater than or equal to 18", "status": 422}
```

An application that wants a structured answer reads `violations()` in a custom
[error handler](../../../flash/docs/errors.md#custom-handlers).

The exception carries every violation the engine reported, in the engine's order, not only the first.

## Who calls it

No codec does. With flash-ext-serde on the classpath, `Validation.module` adds a serde `Check`, and
every protocol decodes through `Decoder.of`: a typed handler's body, an NDJSON line and a gRPC
message are all checked, whatever the codec. A collection or an array is checked by the engine of its
element type. See [serde's codecs.md](../../flash-ext-serde/docs/codecs.md#checks).

A handler that wants to check a value it built itself takes `List<Validation>` as a constructor
parameter and calls `verify` on the engine that `checks` the value's type, or `Validation` when the
app installs one engine.

Without an engine installed, values are parsed and not checked.
