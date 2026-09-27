# flash-ext-view-core

What every template engine module shares; an application depends on an engine module
(`flash-ext-view-jte`, `flash-ext-view-thymeleaf`), not on this one.

- `ViewHandler`: a handler that answers with a template. The engine's annotation names the
  template, `render(Request, Response)` returns its `ViewModel`. Services come through the
  constructor like any other handler.
- `ViewModel`: the values one render sees.
- `ViewModule`: the base of an engine module. `global(key, req -> value)` adds a value every
  template sees, computed per request; at boot, a handler that names a template but is no
  `ViewHandler`, or a `ViewHandler` that names none, fails the start.
- `ViewEngine`: the seam an engine implements. One per app.
