# Schemas

Each Java type is described once under `components.schemas` and referenced by `$ref` wherever it
appears. The name is the class's simple name, or `@Schema(name)`. If two types share a name the
second gets a numeric suffix (`User2`).

## Type mapping

| Java | Schema |
|---|---|
| `String`, `CharSequence` | `string` |
| `boolean`, `Boolean` | `boolean` |
| `byte`, `short`, `int`, `long` and wrappers | `integer` |
| `float`, `double` and wrappers | `number` |
| `UUID` | `string`, `format: uuid` |
| `LocalDate` | `string`, `format: date` |
| `LocalDateTime`, `OffsetDateTime`, `Instant` | `string`, `format: date-time` |
| enum | `string` with the constant names as `enum` |
| `Collection<T>`, arrays | `array` of `T`. A raw collection has object items |
| `Map<K, V>` | `object` with `additionalProperties` of `V` |
| `Object` | `object` |
| anything else | An `object` schema from the type's fields, as a `$ref` |

Fields are read by reflection from the class's declared fields. Static and `transient` fields are
left out.

## Annotations

`@Schema` goes on a type or a field, `@SchemaProperty` on a field or method. They take the same
attributes:

| Attribute | Schema keyword |
|---|---|
| `name` | Schema name on a type; property name on `@SchemaProperty` |
| `title`, `description`, `format`, `example` | The same keyword |
| `enumeration` | `enum` |
| `nullable`, `deprecated` | `nullable: true`, `deprecated: true` |
| `required` | Lists the property under `required` |
| `hidden` | Leaves the property out |

`@ArraySchema(itemClass, uniqueItems, minItems, maxItems)` adds array keywords to a property, and
turns a non-array property into an array of `itemClass`.

```java
@Schema(name = "User", title = "User DTO", description = "Public user")
public record UserDto(
        @SchemaProperty(title = "ID", example = "USR-100") String id,
        @SchemaProperty(hidden = true) String internalDebug) {}
```

## What other modules add

A schema is also shaped by contributors, whichever of these are installed:

- the JSON format module: wire names, ignored fields, read-only and write-only
  ([Jackson](../../flash-ext-jackson-core/docs/openapi.md), [Jsonb](../../flash-ext-avaje-jsonb/docs/usage.md#openapi)),
- the validation engine: bounds, formats and `required` ([contract](../../flash-ext-validation-core/docs/openapi.md)),
- the protobuf module: complete schemas from message descriptors
  ([protobuf](../../flash-ext-protobuf-java/docs/openapi.md)).

Explicit values from `@Schema` and `@SchemaProperty` win over facts a validation engine adds.
