# flash-ext-jackson-xml

XML request bodies and responses through Jackson. Installs the `Xml` codec and provides
`XmlHandler<I, O>`. It behaves exactly like [`flash-ext-jackson-json`](../flash-ext-jackson-json/README.md)
with `application/xml`.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-jackson-xml</artifactId>
</dependency>
```

```java
App app = App.create().install(new XmlModule()).scan("com.acme.api");

@POST("/orders")
final class PlaceOrder extends XmlHandler<Order, Receipt> {
    @Override protected Receipt handle(Request request, Response response, Order order) {
        return new Receipt(order.id());
    }
}
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | Handlers, mapper, using both JSON and XML |
