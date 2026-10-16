# Usage

## Module

`new NdjsonModule(Class<? extends Codec> codec)` names the JSON codec every NDJSON handler uses. It
is the class the codec's module adds: `Json.class` from flash-ext-jackson-json or from
flash-ext-avaje-jsonb. The codec's naming, its validation and its OpenAPI names apply to each line as
they do to a JSON body.

```java
app.install(new JsonModule(), new NdjsonModule(Json.class));
```

## Handler

```java
public abstract class NdjsonHandler<I, O> extends DuplexHandler {
    protected abstract void handle(Request request, NdjsonInput<I> input, NdjsonOutput<O> output) throws Exception;
}
```

`I` is the type each request line is read as, `O` the type each response line is written from.
`Void` on either side means that side carries no lines. The route is declared like any other, with
an annotation or `app.route`.

| Signature | Request | Response |
|---|---|---|
| `NdjsonHandler<Row, Result>` | Lines of `Row`; `Content-Type` must be NDJSON | `200`, `application/x-ndjson` |
| `NdjsonHandler<Void, Event>` | Not read, no `Content-Type` needed | `200`, `application/x-ndjson` |
| `NdjsonHandler<Row, Void>` | Lines of `Row`; `Content-Type` must be NDJSON | `200`, no body |

The request content types accepted are `application/x-ndjson` and `application/jsonl`, with any
parameters and in any case.

```java
@GET("/feed")
final class Feed extends NdjsonHandler<Void, Event> {
    @Override protected void handle(Request req, NdjsonInput<Void> in, NdjsonOutput<Event> out) throws Exception {
        for (Event e : events.live()) { out.send(e); out.flush(); }
    }
}
```

The handler runs after every middleware has returned, like any duplex handler, and inside the scopes
they set up: a `@Transactional` handler reads and writes the whole stream in one transaction.

## Reading

`NdjsonInput.next()` returns the next line's value, or `null` at the end of the body. For a `Void`
input it always returns `null`.

| Rule | Behavior |
|---|---|
| Line end | `\n`. A `\r` before it is dropped |
| Empty line | Skipped. A line of only spaces is not empty and is decoded |
| Last line | Read even without a final `\n` |
| Length | At most `NdjsonInput.MAX_LINE` bytes (1 MiB), `\r` included |
| `null` | A line that decodes to `null` is refused |

A line is decoded where the transport holds it. Only a line split between two reads is copied, once,
into a buffer reused for the request.

## Writing

`NdjsonOutput.send(value)` encodes one value and appends `\n`. Lines collect in a buffer reused for
the whole response and leave:

- when 8 KiB are pending,
- on `flush()`, which also sends them to the client at once,
- when the handler returns.

An export only calls `send`. A live feed calls `flush()` after each line it wants delivered.
`NdjsonOutput` is used from one thread at a time.

## OpenAPI

With flash-ext-openapi on the classpath, `NdjsonModule` adds a contributor that documents each
`NdjsonHandler` route: `application/x-ndjson` as the request body when `I` is not `Void`, and as the
`200` response when `O` is not `Void`. The schema under the media type is the schema of one line,
which is how a sequence is usually written in OpenAPI 3.0. Validation rules and wire names are added
to that schema by the same contributors as for a JSON body.

## Not done

NDJSON routes are not timed by flash-ext-micrometer, like every duplex route. There is no client.
