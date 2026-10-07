# Adding a Jackson data format

Every Jackson format is the same databind model behind a different factory, so a format module needs
a codec class and a module.

```java
public final class Cbor extends JacksonCodec {
    public Cbor(CBORMapper mapper) { super(mapper, ContentType.CBOR); }
}

public abstract class CborHandler<I, O> extends CodecRequestHandler<Cbor, I, Cbor, O> {}

public class CborModule implements Module {
    @Override public void install(App app) {
        app.add(Cbor.class, new Cbor(new CBORMapper())).install(JacksonDocs.module());
    }
}
```

The content type is a constant of `dev.relism.flash.http.ContentType`. If the format has none there,
it has to be added to the core enum first.
