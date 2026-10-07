# Production

Outside dev, the build is read once at boot from `flash-vite/` on the classpath, whether that is a
directory or a jar. A missing `flash-vite/index.html` fails the boot and names the plugin. Files are
served by core's [`StaticFiles`](../../../flash/docs/static-files.md): everything a response carries is
prepared at boot.

## Responses

For `GET` and `HEAD` on `basePath/**`:

| Request | Answer |
|---|---|
| `basePath` itself | `index.html` |
| A built file under `assets/` | `Cache-Control: public, max-age=31536000, immutable`, no `ETag` |
| Any other built file | `Cache-Control: no-cache`, with an `ETag` |
| `If-None-Match` with the current `ETag` | `304` |
| No such file, and the request is a navigation | `index.html`, so the client-side router handles it |
| No such file, otherwise | The app's `404` |

A `.gz` beside a file is sent to clients that accept gzip, with `Vary: Accept-Encoding`. The plugin
writes them at build time.

## Fallback

A request is a navigation when it has `Sec-Fetch-Mode: navigate` (sent by current browsers when a
person opens a URL) or an `Accept` that names `text/html` (older browsers, crawlers). This is
stricter than Vite's own dev fallback, which also serves the page for `Accept: */*`. As a result
`fetch('/api/typo')` gets a `404` instead of the page.

`navigationOnly(false)` serves `index.html` for every `GET` that matches no file, like nginx's
`try_files`. API 404s then become the page.

## Limits

- Every file is held in memory, gzip included. This suits an app's own frontend. Serve large media
  from a CDN or another route.
- A browser navigating directly to a nonexistent API path gets the app, which shows its own
  not-found screen. API clients get the `404`.
