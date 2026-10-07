# Handlers, models and globals

## ViewHandler

A `RequestHandler` that answers with a rendered template. The engine's annotation names the template;
you build the model.

```java
public abstract class ViewHandler extends RequestHandler {
    public abstract ViewModel render(Request req, Response res) throws Exception;
}
```

At boot the handler looks up the app's `ViewEngine` and asks it for the template its class names. A
handler that names none fails the boot. `render` runs per request, and the engine renders its result.
Services come through the constructor like any handler.

An annotated class that is not a `ViewHandler` also fails the boot: `X names a template but is no
ViewHandler`.

## ViewModel

A mutable map for one request. Maps, lists and nested `ViewModel`s are converted to unmodifiable
copies, so templates see plain values.

| Method | Effect |
|---|---|
| `ViewModel.of(key, value)`, `empty()` | New model |
| `with(key, value)` | Adds a value. The key must not be null |
| `withAll(map)` | Adds every entry |
| `merge(other)` | A new model with `other`'s values over this one's |
| `toMap()` | An unmodifiable view |
| `copyOf(model)` | An independent copy |

## Globals

A global is a value every template sees, computed per request.

```java
new JteModule().global("user", req -> currentUser(req))
```

`global(key, resolver)` rejects a blank key or one containing `.`, and a duplicate key. A global is
resolved on every render, so keep it cheap. How a template reads it depends on the engine: jte merges
globals flat into the model, and Thymeleaf puts them under `global`. A model key that collides with a
global fails the render.

## Writing an engine

An engine module extends `ViewModule` and returns a `ViewEngine`:

| Method | Purpose |
|---|---|
| `Object target(Class<?> handler)` | Reads the handler's annotations at boot. Returns what to render, or `null` if the class names none |
| `Object render(Object target, ViewModel model, Request req, Response res)` | Renders. Returns the response body and sets its content type on `res` |

`ViewModule.install` registers the engine as a service and a route hook that checks every route at
boot.
