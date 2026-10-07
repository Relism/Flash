# Rules in the OpenAPI document

An engine can say what its rules mean, so the published document describes what is enforced. The
core knows no constraint by name: the engine registers the annotations it enforces.

```java
@Override public void describe(Constraints constraints) {
    constraints.of(Pattern.class, (rule, facts) -> {
        facts.put("pattern", rule.regexp());
        facts.describe(rule.message());
    });
    constraints.of(NotNull.class, (rule, facts) -> facts.required());
}
```

`describe` is called once, when the document is first built. The function for each annotation runs
per property when the document is built, not on a request.

## Facts

| Method | Effect on the property's schema |
|---|---|
| `type()` | The property's Java type, for rules that mean different things on text and on a list |
| `put(keyword, value)` | Sets a schema keyword (`maxLength`, `minimum`, `format`). Never overwrites a value already set by an explicit `@Schema` |
| `describe(sentence)` | Appends a sentence to the description. Ignored if blank or a message-bundle key (starts with `{`). A full stop is added if missing |
| `required()` | Lists the property under `required` |

`Validation.docs()` installs the walk that applies registered rules to every annotated field. It is
loaded only when `flash-ext-openapi` is present.
