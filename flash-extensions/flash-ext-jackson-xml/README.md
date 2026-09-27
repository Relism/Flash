# flash-ext-jackson-xml

XML bodies and XML responses, over the same types as every other Jackson format.

```java
app.install(new XmlModule());
```

```java
@POST("/orders")
public final class PlaceOrder extends XmlHandler<Order, Receipt> {
    private final OrderService orders;

    public PlaceOrder(OrderService orders) { this.orders = orders; }

    @Override protected Receipt handle(Request req, Response res, Order body) {
        return orders.place(body);
    }
}
```

Everything [`flash-ext-jackson-json`](../flash-ext-jackson-json) does, in XML: the body is read off
the request stream, verified against the constraints its type declares, and handed over. A type
annotated for JSON works here as is — `Order` can be a JSON body on one route and an XML body on
another.

A route that only delivers XML reads nothing:

```java
@GET("/catalogs/{locale}")
public final class Catalog extends XmlHandler<Void, Resources> { … }
```

And a route may mix the two formats, because each end names its own codec:

```java
public final class Ship extends CodecHandler<Xml, Order, Json, Receipt> { … }
```

What is specific to XML is Jackson's own: `@JacksonXmlRootElement` for the root name,
`@JacksonXmlProperty(isAttribute = true)` for an attribute rather than an element, and
`@JacksonXmlElementWrapper` for how a list is wrapped. This module adds no annotations of its own.

Brings `jackson-dataformat-xml`, and with it Woodstox.
