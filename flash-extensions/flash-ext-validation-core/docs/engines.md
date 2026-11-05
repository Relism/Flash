# Writing an engine

An engine implements `Validation`, usually also `Module`, and installs itself:

```java
public class MyValidation implements Module, Validation {
    @Override public void install(App app) {
        app.install(Validation.module(this));
    }

    @Override public boolean checks(Class<?> type) {
        return hasRules(type);   // asked once per decoded type, at boot
    }

    @Override public void verify(Object value) {
        List<ValidationException.Violation> failures = check(value);
        if (!failures.isEmpty()) throw new ValidationException(failures);
    }
}
```

`Validation.module(engine)` adds the engine as a `Validation`. When `flash-ext-serde` is on the
classpath it also checks every decoded value of a type the engine `checks`, and when
`flash-ext-openapi` is, it adds the engine's rules to the published document. Each is skipped when
its module is absent.

## Several engines

An engine that answers `checks` precisely lives beside others. Protovalidate checks only protobuf
messages; the Jakarta and Avaje engines check a type that declares a constraint or a cascade, so a
generated message is not theirs. One application therefore has a Jakarta engine for JSON and a
Protovalidate engine for protobuf without any configuration.

Two engines that both check one type fail the boot at the first handler that decodes it, naming
both. `checks` defaults to `true`, so two engines that keep the default are refused as soon as a body
is decoded.
