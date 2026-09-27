# flash-ext-validation-core

One contract for checking what a request carried, and one way for the engine behind it to describe
its rules. No engine of its own: install
[`flash-ext-validation-jakarta`](../flash-ext-validation-jakarta) or
[`flash-ext-validation-avaje`](../flash-ext-validation-avaje).

```java
app.install(new JsonModule())
   .install(new JakartaValidation());
```

That is the whole of it. A JSON or XML body is checked as soon as it is read: a malformed body is a
400, a body that breaks a rule is a 422 listing every failure, and neither reaches the handler.

```json
{"error": "name must not be blank; address.street must not be blank", "status": 422}
```

## One engine, said out loud

Two engines in one application is a boot failure naming the second. Which engine validated a request
is not a thing to leave to install order, and an application that installs none gets one line at
boot saying that bodies are parsed and not checked.

## The contract

```java
public interface Validation {
    void verify(Object value);                              // 422 on failure, nothing otherwise
    default void describe(Constraints constraints) {}       // what its rules mean, for the document
}
```

`verify` is the only thing on the request path, and it is the engine's own work: this module
allocates nothing there.

## The document

An engine declares what its annotations mean, and the published OpenAPI document carries it — no
annotation is known by name here:

```java
@Override public void describe(Constraints constraints) {
    constraints.of(Size.class, (size, facts) -> {
        if (size.max() != Integer.MAX_VALUE) facts.put("maxLength", size.max());
    });
    constraints.of(Pattern.class, (pattern, facts) -> {
        facts.put("pattern", pattern.regexp());
        facts.describe(pattern.message());      // a regex has no keyword a reader understands
    });
    constraints.of(NotNull.class, (notNull, facts) -> facts.required());
}
```

Consequences worth knowing:

- **The document says what is enforced.** Two engines that share an annotation may describe it
  differently, because each documents what it does with it. An application with no engine installed
  gets a document with no rules in it, which is the truth.
- **A fact never overwrites what the code already said.** An explicit `@Schema` description or bound
  wins; the engine fills what was left unsaid.
- **It runs once per document revision**, never on a request, so nothing here is on a hot path.

`flash-ext-openapi` knows nothing about validation: this module contributes through its contributor
interface, and is silent when it is not on the classpath.

## Which engine

| | [jakarta](../flash-ext-validation-jakarta) | [avaje](../flash-ext-validation-avaje) |
|---|---|---|
| How | Hibernate Validator, reflection at runtime | generated adapters, no reflection |
| Asks of the build | nothing | its annotation processor, and `@Valid` on each validated type |
| Brings | hibernate-validator, expressly | avaje-validator |
| Reach for it when | you want it to work with no build change | the request path is measured |

Both read the jakarta constraint annotations, so a type is annotated once whichever one is
installed. What is not portable is a *custom* constraint: it is written against one engine's SPI and
ties the application to it.
