# Dev Lifecycle

Boot flow in dev mode:

1. Resolve runtime mode.
2. Validate configuration and command safety.
3. Optionally install dependencies (hash-based lockfile cache).
4. Start dev server process.
5. Run healthcheck.
6. Start watchlist loop and restart dev process when tracked files change.

Failure conditions are fail-fast:

- occupied dev port
- command launch failure
- healthcheck timeout
