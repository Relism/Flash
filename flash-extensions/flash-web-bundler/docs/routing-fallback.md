# Routing Fallback

Fallback policy:

- backend routes always have precedence
- fallback route is wildcard under configured `basePath`
- fallback applies to `GET` and `HEAD` only
- if no static asset matches, serve SPA `indexFile`

Namespace collisions should be avoided at app design level (for example, keep API under `/api`).
