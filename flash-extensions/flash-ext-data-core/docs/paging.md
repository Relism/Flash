# Paging

A list is served in pages that a client walks with a token: each page says where the next one starts.
The client chooses the size and the order among the fields the type allows; the position travels in
`after`. Pages are keyset pages: a page costs the same wherever it is in the list, no count is run,
and rows inserted between two requests are neither repeated nor skipped.

```java
public record User(long id, @Sortable String name, @Sortable Instant lastLogin) {}

@GET("/users")
final class ListUsers extends JsonHandler<PageRequest<User>, Page<User>> {
    private final Repository<User, Long> users;
    ListUsers(Data data) { this.users = data.repository(User.class); }

    @Override protected Page<User> handle(Request request, Response response, PageRequest<User> page) {
        return users.findPage(page);
    }
}
```

```
GET /users?sort=-lastLogin&size=50                 {"content":[...],"next":"AQAF..."}
GET /users?sort=-lastLogin&size=50&after=AQAF...   {"content":[...],"next":null}
```

## Query parameters

| Parameter | Value | Default |
|---|---|---|
| `size` | Items in the page, from 1 to `Pager.MAX_SIZE` (100) | `Pager.DEFAULT_SIZE` (20) |
| `sort` | The id or a `@Sortable` field, by its wire name. A leading `-` reverses it | The id, ascending |
| `after` | The `next` of the previous page | The first page |

The first non-empty value of each parameter counts; an absent or empty one is the default. Anything
else is a `400`:

| Request | Error |
|---|---|
| `size` not a number from 1 to 100 | `size must be a number from 1 to 100` |
| `sort` neither the id nor a `@Sortable` field | `sort must be one of id, name, lastLogin, with - to reverse it` |
| `after` not a token of this type | `after is not a value this API issued` |
| `after` from a page with another `sort` | `after belongs to a page with another sort` |

`size` may change from one page to the next.

## Order

A page is sorted by its field in the direction asked, then by the id in the same direction, so two
rows never tie. Rows whose field is null come after every other row in both directions, ordered by id.
Sorting by the id alone uses the id only.

`Repository.findPage` reads one row more than the page holds, to know whether a page follows. On a
nullable field the page where the values end is completed with the nulls by a second query. The
first page a repository answers for a type also names every sortable column once, so a wrong column
fails there, whichever sort it belongs to.

## Sortable fields and the id

`@Sortable` goes on the fields a client may sort by. Only those and the id are accepted: sorting runs
in the database, so mark the fields that have an index. A field without it cannot be sorted by, nor
can a client learn anything about its values through the order.

The id is the field annotated `jakarta.persistence.Id`, or else the field named `id`. Fields of superclasses count, theirs first.

A key is a `String`, a primitive or its wrapper, `BigDecimal`, `BigInteger`, `UUID`, `Instant`,
`LocalDate`, `LocalTime`, `LocalDateTime` or `OffsetDateTime`. Building the pager fails with
`IllegalStateException` for a type without an id, a key of another type, a static `@Sortable` field or
two keys with one wire name.

## The token

`next` is opaque: it holds the sort it was issued for and the text form (`toString`) of the last
item's field value and id, encoded in base64url. It is not signed: its values are bound as query parameters, so changing one only moves
the position in the list. A token stays valid as long as the type's sortable fields do not change.

## Pager

`Pager<T>` reads the parameters for one type. It is built once and shared.

| Member | Behavior |
|---|---|
| `Pager.of(type)` | Fields named as in Java |
| `Pager.of(type, names)` | Fields named by a `Function<Field, String>`, such as a codec's `wireName` |
| `read(request)` | The `PageRequest<T>`, or a `400`. A request without paging parameters gets one instance built ahead, and nothing is allocated |
| `sorts()` | Every accepted `sort` value: `id`, `-id`, then each field and its reverse |

`PageRequest<T>` has nothing to read: it is handed to `findPage`, and belongs to the request it was
read from. `Page<T>` is a record of `content` and `next`, and `map(function)` returns the same page
with each item mapped, for an answer made of views of the entities.

## Repository

| Method | Returns |
|---|---|
| `findPage(request)` | The page `request` asks for |
| `findPage(request, scope)` | The same among the rows `scope` matches, such as one tenant's |

The keys are turned into columns by the repository's `column(property)`: see
[backends.md](backends.md#repository).

## With flash-ext-serde

A handler whose input type is `PageRequest<T>` reads it from the query, never from the body:
`PageRequest` is `@FromRequest`. `DataModule` registers the `InputBinder` that reads it when
flash-ext-serde is on the classpath, and builds the pager at boot with the field names of the
handler's input codec, so `sort` uses the names the JSON does. The boot fails without `DataModule`,
for a raw `PageRequest`, and for one over a type that cannot be paged.

## With flash-ext-openapi

For such a handler the document lists `size`, `sort` (with every accepted value) and `after`, a `400`
response, and no request body. `Page<User>` is the schema `PageUser`, with `content` required and
`next` nullable.

A handler that calls `Pager.read` itself is not seen: declare its parameters with `@Parameter`.

## With Avaje Jsonb

This jar registers the adapter of `Page` through Jsonb's `ServiceLoader`; nothing has to be declared.
It writes `"next":null` on the last page even when Jsonb leaves nulls out, as it does by default.
