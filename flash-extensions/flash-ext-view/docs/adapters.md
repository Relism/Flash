# Adapters

`ViewEngineAdapter` is the rendering boundary.

## Built-in

- `ViewEngineType.THYMELEAF`

## Custom adapter

```java
public final class MyAdapter implements ViewEngineAdapter {
    @Override
    public EngineCapabilities capabilities() {
        return EngineCapabilities.NONE;
    }

    @Override
    public RenderOutput render(ViewTarget target,
                               Map<String, Object> model,
                               Request req,
                               Response res) {
        String body = "...";
        return RenderOutput.html(body);
    }
}
```

Adapter instances must be thread-safe after construction.
