# flash-ext-jackson-core

What every Jackson data format shares. Applications do not install this module directly: they
install a format — [`flash-ext-jackson-json`](../flash-ext-jackson-json),
[`flash-ext-jackson-xml`](../flash-ext-jackson-xml) — and get all of this with it.

## Why there is a core at all

Every Jackson data format is the same databind model behind a different factory: `XmlMapper`,
`YAMLMapper` and `CBORMapper` are all `ObjectMapper`s. So the annotations on a type, the
constraints its fields declare and the schema it publishes are the same whatever writes it. Only
the mapper and the content type differ, and that is all a format module has to say.

## Codec

One mapper, in the shape a handler needs it.

| | |
|---|---|
| `body(Request, Class<T>)` | Parses the body and verifies its constraints |
| `write(Response, Object)` | Serializes and sets the format's content type |
| `writeView(Response, Object, Class<?>)` | The same, through a Jackson `@JsonView` |
| `mapper()` | The `ObjectMapper` itself, for everything else |

`body` reads straight off the request's stream, which Flash reuses per connection: the body is
never buffered into an array to be handed over. A malformed body is a 400, a body that breaks a
constraint is a 422, and neither reaches the handler.

## Constraints

A body is checked against the `jakarta.validation` annotations its own type declares — nothing to
install, nothing to call:

```java
public record NewUser(@NotBlank @Size(max = 80) String name, @Email String email, @Min(18) int age) {}
```

Supported: `@NotNull`, `@NotBlank`, `@NotEmpty`, `@Size`, `@Min`, `@Max`, `@Email`, `@Pattern`.
Jakarta semantics: only `@NotNull` rejects null, every other constraint passes it.

A failure reads `<field> <message>`, and the message is the constraint's own when it sets one —
which is how the person who sent the request is told something better than a regex:

```java
@Pattern(regexp = "[A-Za-z0-9_][A-Za-z0-9_.-]{0,254}", message = "uses up to 255 letters, digits, _, . and -")
String key
```

```json
{"error": "key uses up to 255 letters, digits, _, . and -", "status": 422}
```

The constraints of a type are compiled the first time it is seen and kept in a `ClassValue`,
beside the class itself — no map, no lock. A check reads the field through an exact-signature
`MethodHandle`: no boxing, no argument array, no iterator, and nothing allocated at all unless
something fails. A type that declares no constraints compiles to a validator that does nothing.

Verify a value built by hand with `Validator.check(value)`.

`flash-ext-openapi` reads the same annotations to publish `minLength`, `maximum`, `pattern` and
the required fields, so a rule is written once and both enforced and documented.

## Writing a format module

```java
public final class Yaml extends Codec {
    public Yaml(YAMLMapper mapper) { super(mapper, ContentType.TEXT_YAML); }
}

@Consumes(ContentType.TEXT_YAML)
public abstract class YamlHandler<B> extends JacksonHandler<B> {
    @Inject private Yaml yaml;
    @Override protected Codec codec() { return yaml; }
}
```

Plus an extension that provides the codec and, for outbound bodies,
`Marshalling.of(mapper, contentType)`. That is the whole of it.
