# flash-ext-jackson-core

What every Jackson data format shares. Applications do not install this module directly: they
install a format — [`flash-ext-jackson-json`](../flash-ext-jackson-json),
[`flash-ext-jackson-xml`](../flash-ext-jackson-xml) — and get all of this with it.

## Why there is a core at all

Every Jackson data format is the same databind model behind a different factory: `XmlMapper`,
`YAMLMapper` and `CBORMapper` are all `ObjectMapper`s. So the annotations on a type, the
constraints its fields declare and the schema it publishes are the same whatever writes it. Only
the mapper and the content type differ, and that is all a format module has to say.

## JacksonCodec

One mapper, as a [`Codec`](../flash-ext-serde): `read`, `write`, `contentType`, plus `mapper()` for
everything those do not cover.

`read` reads straight off the request's stream, which Flash reuses per connection: the body is
never buffered into an array to be handed over. It is handed the type the handler named, a generic
one included, so `JsonHandler<List<Order>, …>` parses what it says.

## Constraints

A body is checked as soon as it is read, by whichever validation engine the application installed —
see [`flash-ext-validation-core`](../flash-ext-validation-core). This module only asks: a malformed
body is a 400 of its own making, a body that breaks a rule is the engine's 422, and neither reaches
the handler. With no engine installed a body is parsed and not checked, and that is said once at
boot.

Nothing to register: the codec binds the engine itself when the application boots.

## Writing a format module

```java
public final class Yaml extends JacksonCodec {
    public Yaml(YAMLMapper mapper) { super(mapper, ContentType.TEXT_YAML); }
}

public abstract class YamlHandler<I, O> extends CodecHandler<Yaml, I, Yaml, O> {}
```

Plus an extension that provides the codec and calls `CodecDocs.register(ctx)`. That is the whole
of it: reading, writing, constraints and the document come from
[`flash-ext-serde`](../flash-ext-serde) and this module.
