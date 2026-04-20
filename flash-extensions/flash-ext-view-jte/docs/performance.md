# Performance Notes

- Runtime caches resolved targets by handler class.
- Request path allocates only what jte rendering requires.
- No reflection on hot path after target cache is warm.
- `TemplateEngine` is created once and shared.

## Dev vs Prod

- Dev mode (`developmentMode=true`) uses code resolver rendering.
- Prod mode (`usePrecompiled=true`) uses precompiled classes for startup and render speed.

## Content Type

`gg.jte.ContentType` drives both escaping mode and HTTP response content type mirror:

- `Html` -> context-aware HTML escaping + `text/html`
- `Plain` -> plain output + `text/plain`
