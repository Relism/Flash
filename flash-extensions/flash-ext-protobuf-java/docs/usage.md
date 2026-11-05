# Using protobuf

## ProtobufHandler

```java
public abstract class ProtobufHandler<I, O> extends CodecRequestHandler<Protobuf, I, Protobuf, O> {}
```

`I` and `O` are generated message classes (`com.google.protobuf.Message`, with a static
`getDefaultInstance()`). Any other type fails the boot with `IllegalArgumentException`. `Void` as `I`
reads nothing.

## Codec behavior

- A body is read whole and parsed from the array with the message's parser.
- A body that does not parse is a `400`, `Invalid protobuf message: <reason>`.
- An answer is written into the response buffer; its serialized size is computed first, so no
  intermediate array is created.
- Content type is `application/protobuf`.

## Extensions

`new ProtobufModule(extensionRegistry)` parses messages that carry protobuf extension fields with
the given `ExtensionRegistryLite`. `new ProtobufModule(protobuf)` takes a `Protobuf` you built.

## Validation

`Protobuf` only parses. A message is checked by the validation engine that checks its type, as any
decoded value is; see [flash-ext-validation-core](../../flash-ext-validation-core/README.md). The
engine for protobuf messages is
[flash-ext-validation-protovalidate](../../flash-ext-validation-protovalidate/README.md), and it lives
beside a Jakarta or Avaje engine for JSON and XML. Without an engine that checks messages, they are
parsed and not checked.

## Direct use

`Protobuf` is a service with `decoder(type)`, `write(response, value)` and `contentType()`. A plain
handler parses through `Decoder.of`, prepared once in `onInit`, which applies the engine:

```java
orders = Decoder.of(protobuf, Order.class, services());   // in onInit
Order order = (Order) orders.decode(request);
return protobuf.write(response, reply);
```
