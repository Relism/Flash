# The validation contract

## Validation

```java
public interface Validation {
    void verify(Object value);
    default void describe(Constraints constraints) {}
}
```

`verify` checks a value against the rules its own type declares and throws `ValidationException`
when it breaks any. Which annotations exist and how they are enforced is the engine's business.

Engines are registered as `Validation` services (`app.add(Validation.class, engine)`). A second
engine added the same way fails the boot, because codecs look the service up with `find` and
`find` fails on several.

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

The codecs do: the Jackson JSON and XML codecs and the Avaje Jsonb codec call `verify` on each body
they parse. The protobuf codec calls a protobuf engine, see [engines.md](engines.md#codec-families).
A handler that wants to check a value it built itself takes `Validation` as a constructor parameter
and calls `verify`.

Without an engine installed, codecs parse and do not check, and log one info line at boot.
