# Writing an engine

An engine implements `Validation`, usually also `Module`, and installs itself:

```java
public class MyValidation implements Module, Validation {
    @Override public void install(App app) {
        app.add(Validation.class, this).install(Validation.docs());
    }

    @Override public void verify(Object value) {
        List<ValidationException.Violation> failures = check(value);
        if (!failures.isEmpty()) throw new ValidationException(failures);
    }
}
```

`Validation.docs()` returns a module that adds the engine's rules to the OpenAPI document when
`flash-ext-openapi` is on the classpath, and does nothing otherwise.

## Codec families

A codec whose values the generic engines cannot check defines a sub-interface of `Validation`. The
protobuf codec defines `ProtobufValidation`. The engine is registered under the sub-interface, and
the codec looks it up with:

```java
Validation.family(services, ProtobufValidation.class)   // Optional<ProtobufValidation>
```

`family` fails the boot if a `ProtobufValidation` was registered as a plain `Validation`, where its
codec would never ask it. As a result one application can have a Jakarta engine for JSON and a
Protovalidate engine for protobuf at the same time.
