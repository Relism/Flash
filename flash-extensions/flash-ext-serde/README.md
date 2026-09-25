# flash-ext-serde

Handlers that name a codec and a type for each end. No serialization library of its own: a format
module brings one — [`flash-ext-jackson-json`](../flash-ext-jackson-json),
[`flash-ext-jackson-xml`](../flash-ext-jackson-xml) — and this is the shape they plug into.

## The two ends are independent

```java
@POST("/orders")
public final class PlaceOrder extends CodecHandler<Xml, Order, Json, Receipt> {
    @Inject private OrderService orders;

    @Override protected Receipt handle(Request req, Response res, Order order) {
        return orders.place(order);
    }
}
```

That handler reads XML and answers JSON. Any pair works, because the pair is not a class: it is
four type arguments, and a format module names the symmetric case for its own users —
`JsonHandler<I, O>` is `CodecHandler<Json, I, Json, O>` and nothing more.

`Void` as the request type reads nothing, which is how a route that only answers is written:

```java
@GET("/catalogs/{locale}")
public final class Catalog extends XmlHandler<Void, Resources> {
    @Override protected Resources handle(Request req, Response res, Void nothing) { … }
}
```

Both codecs are resolved once at boot, from the context, by the classes in the signature. A codec
nothing provides fails the boot naming it; a handler that leaves its type arguments open fails it
too, rather than guessing.

## Codec

One format, both directions. Three methods, and what an implementation does inside — which
library, what it checks on the way in — is its own business.

| | |
|---|---|
| `read(Request, Type)` | The body as that type, generic types included |
| `read(Request, Class<T>)` | The same, where the compiler can check what comes back |
| `write(Response, Object)` | Serialized, with this codec's content type set on the response |
| `contentType()` | What it writes, which is also what the document says it writes |

An answer that is already a body — `null`, a `Response`, a `byte[]` or a `CharSequence` — is
passed through untouched, so a handler that writes its own bytes still works.

## A codec outside a handler

A route written as a lambda has no signature to read: it calls the codec.

```java
app.get("/health", (req, res) -> json.write(res, status));
```

That is the same call the handler base makes, and the only way a body is produced in Flash:
nothing serializes behind a handler's back.

## The document

`flash-ext-openapi`, when it is on the classpath, is told each operation's media types by this
module: the codecs in the signature are asked what they write. Nothing is declared twice, and the
document cannot disagree with what runs. A format extension wires that up with one call after
providing its codec:

```java
@Override public void configure(FlashRegistrar<?> app, FlashContext ctx) {
    ctx.provide(Yaml.class, new Yaml(mapper));
    CodecDocs.register(ctx);
}
```
