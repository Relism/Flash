# Using XML

## XmlHandler

```java
public abstract class XmlHandler<I, O> extends CodecRequestHandler<Xml, I, Xml, O> {}
```

`I` is the body type (`Void` for none) and `O` the answer type. Answers carry
`Content-Type: application/xml`. A malformed body is a `400`, and a validation failure a `422`, as
for JSON.

## Mapper

`new XmlModule()` uses `XmlMapper.builder().findAndAddModules().build()`. Pass your own with
`new XmlModule(xmlMapper)`.

XML concerns (root element name, attributes instead of elements, list wrapping) are expressed with
Jackson's `@JacksonXmlRootElement`, `@JacksonXmlProperty` and `@JacksonXmlElementWrapper` on the
type.

## JSON and XML in one application

Install both modules. A route picks its format by the handler it extends. The same annotated class
can be read as XML on one route and as JSON on another.

```java
app.install(new JsonModule(), new XmlModule());
```

A route that reads XML and answers JSON names both codecs:

```java
final class Convert extends CodecRequestHandler<Xml, Order, Json, Receipt> { ... }
```

## Direct use

`Xml` is a service with `read(request, type)`, `write(response, value)` and `mapper()`.
