# Changelog

## [3.0.1](https://github.com/Relism/Flash/compare/v3.0.0...v3.0.1) (2026-10-16)


### Bug Fixes

* **core:** declare ServerConfigBuilder so the aggregated JavaDoc builds ([#11](https://github.com/Relism/Flash/issues/11)) ([b0cdce9](https://github.com/Relism/Flash/commit/b0cdce9e471e4d99b8ff4c82539c24ecc5480f70))

## [3.0.0](https://github.com/Relism/Flash/compare/v2.0.0...v3.0.0) (2026-10-16)


### ⚠ BREAKING CHANGES

* **core:** applications move from FlashApp and extensions to App, Module and Server.start; handlers take services through their constructor.

### Features

* **ci:** add workflow for publishing JavaDoc to GitHub Pages ([9a2fdf6](https://github.com/Relism/Flash/commit/9a2fdf67eeed58c2c9b88c12528b8801a94b532f))
* **core:** a service a handler asks for, and a body typed in its signature ([519f39d](https://github.com/Relism/Flash/commit/519f39df679e51931249f632b1ada5f9c22feafa))
* **core:** add any() route registration, warn on undocumented OpenAPI routes, log unhandled 500s ([4969d09](https://github.com/Relism/Flash/commit/4969d09d3d2534bcbafa8f728272c13b87403e02))
* **core:** add HPACK coding primitives ([afa4ea4](https://github.com/Relism/Flash/commit/afa4ea46067fe9da0d401448eea004c935a7ef21))
* **core:** add HPACK decoder ([77ea857](https://github.com/Relism/Flash/commit/77ea857545af4dcc51b7eb3c57553d83062543b6))
* **core:** add HTTP QUERY method support ([d4f52f6](https://github.com/Relism/Flash/commit/d4f52f666b06cc5167c754d0bf19f0cabfc1937d))
* **core:** add HTTP trailers and push streaming ([bb39527](https://github.com/Relism/Flash/commit/bb395278ceefb9387df7d191971641207b07e91c))
* **core:** add HTTP/2 cleartext proxy support ([34db95f](https://github.com/Relism/Flash/commit/34db95ff936cab95dfc070b54171587c12f4ed7d))
* **core:** add HTTP/2 connection state machine ([59fcaf6](https://github.com/Relism/Flash/commit/59fcaf6cdb66360d3cf41bd71e483f47c70b8322))
* **core:** add HTTP/2 flow-controlled bodies ([9157bc1](https://github.com/Relism/Flash/commit/9157bc1de1be613ae7df14f7f353d72ca1f32494))
* **core:** add HTTP/2 performance gates ([92ac564](https://github.com/Relism/Flash/commit/92ac564329f6329619bac494b9517834b5ec0f74))
* **core:** add HTTP/2 response path ([1643676](https://github.com/Relism/Flash/commit/1643676fd20ed4263858163e7f6356a1ba3b798a))
* **core:** add HTTP/2 stream dispatch ([fd66ee5](https://github.com/Relism/Flash/commit/fd66ee513ad6b1b87ac9d715e328a1f24588a9ed))
* **core:** add request helpers for security flows ([15329be](https://github.com/Relism/Flash/commit/15329beef2ebe6060e55fed73e5669662f07fa5c))
* **core:** add TLS/mTLS support with multi-listener and SNI ([d995fb9](https://github.com/Relism/Flash/commit/d995fb94447aca8993ea0c943d3b02ea3265d06c))
* **core:** add WebSocket over HTTP/2 ([da227e4](https://github.com/Relism/Flash/commit/da227e47ae501d2b7424b96073c078128e2a45bf))
* **core:** ALPN configuration and TLS visibility on Request/WebSocketSession ([4bb2f2b](https://github.com/Relism/Flash/commit/4bb2f2b5bd49760b052164ba9bb9eef0d9e1491f))
* **core:** an app of modules and services, built through constructors ([7b832fd](https://github.com/Relism/Flash/commit/7b832fdd3d7184fe4a631fbf95a7fd92f7febb68))
* **core:** expose bound ports, service override, FlashApplication and close hooks ([572f186](https://github.com/Relism/Flash/commit/572f1863a4a9e5e74d7afc9e96d3a1c5ec67b103))
* **core:** harden HTTP/2 abuse resistance ([8a84f64](https://github.com/Relism/Flash/commit/8a84f64fdf429768df61313d955c4db2f97ee66e))
* **core:** HTTP/2 Phase 0 — groundwork (limits, error model, decision log) ([c8fbfc7](https://github.com/Relism/Flash/commit/c8fbfc71da8ffcda8c99f03badd74a474e77a389))
* **core:** HTTP/2 Phase 1 — HTTP/1.1 hardening and protocol negotiation ([b7bdc25](https://github.com/Relism/Flash/commit/b7bdc25c6957592bca8519e0396476057031d9da))
* **core:** HTTP/2 Phase 2 — transport decomposition ([2518ed5](https://github.com/Relism/Flash/commit/2518ed50e9009845554d1cc1288dc91fb58c7a29))
* **core:** HTTP/2 Phase 3 — serialized frame writer (GO/NO-GO gate) ([658598e](https://github.com/Relism/Flash/commit/658598e2df05887b8b97a322dc7312a2553da403))
* **core:** HTTP/2 Phase 4 — byte-layer foundations ([e4ec4ac](https://github.com/Relism/Flash/commit/e4ec4ac00454d80cec8495163e1085ded0f3b71a))
* **core:** HTTP/2 Phase 5 — frame layer ([5e05ac7](https://github.com/Relism/Flash/commit/5e05ac750e43dd940a42d6539c65e274748b023b))
* **core:** HTTP/2 Phase 6 — Request/Response model refactor ([80f9a35](https://github.com/Relism/Flash/commit/80f9a350580e765b3854542a841dfb8845593cf7))
* **core:** let infrastructure wrap a completed exchange ([b524d03](https://github.com/Relism/Flash/commit/b524d03ad9af5f0207ef89c9a0f2c46f3e9b574d))
* **core:** protocol extensions: gRPC-Web, NDJSON, WebDAV and HTTP Basic ([4756a20](https://github.com/Relism/Flash/commit/4756a208e02dfc87fb737c93ffb0542859370dc7))
* **core:** request handlers, duplex framing, SSE and a rewritten documentation set ([4e77d57](https://github.com/Relism/Flash/commit/4e77d57cce37b1ec5a3a1b3249e3bfde3c7db863))
* **core:** WS client-mode masking, zero-copy header iteration, shared I/O relay buffer ([d71cb41](https://github.com/Relism/Flash/commit/d71cb4157e340050aa377ae4c30a4b9157ef5fb7))
* **data:** actually invoke TxSynchronization.beforeCommit, and document the contract ([0a83343](https://github.com/Relism/Flash/commit/0a83343cee9a9ffeaaeb42d8a0559d792e20f2c9))
* **ext-cache-core:** add the caching contract and a Caffeine backend ([a12f9f5](https://github.com/Relism/Flash/commit/a12f9f532874b4495091e1577999bc9fd4a3a2d3))
* **ext-data:** add unified Data gateway; mcp: derive roles claim from OIDC ([88096d4](https://github.com/Relism/Flash/commit/88096d4723c5268b78a56630d19542e814656c4a))
* **ext-data:** close the connection pool when the app stops ([ac71e9c](https://github.com/Relism/Flash/commit/ac71e9cf83c2e3c478a3b35d9875a89813152703))
* **ext-jackson:** one module per data format, bodies checked on the way in ([510702d](https://github.com/Relism/Flash/commit/510702d03f86e4fb0d055dfcd56acedd4e210aa8))
* **ext-mcp:** add MCP (Model Context Protocol) server extension ([47cbfc8](https://github.com/Relism/Flash/commit/47cbfc8def036d1594e11d6f2e517582ab44e61d))
* **ext-mcp:** let applications put middleware on the MCP route, and document the auth split ([1480046](https://github.com/Relism/Flash/commit/1480046c7d1e408abe7834d06663af699d3ef7aa))
* **ext-micrometer:** HTTP server metrics and a Prometheus endpoint ([319835d](https://github.com/Relism/Flash/commit/319835d59ce75cd43326b771f88cec186445c342))
* **ext-openapi:** a pattern says in words what its regex says in symbols ([dda6ff1](https://github.com/Relism/Flash/commit/dda6ff189c690f1d9b46400ccad1c668f6e1c68e))
* **ext-openapi:** a route can say it is not part of the API ([9cfce28](https://github.com/Relism/Flash/commit/9cfce28aa3ef2b44d045902a65f276cd568e986c))
* **ext-openapi:** document what the code already says ([1b24686](https://github.com/Relism/Flash/commit/1b246864eb4b8af277062d526c73be6ba1bec96a))
* **ext-openapi:** let a contributor describe a type and learn which are used ([f392fd2](https://github.com/Relism/Flash/commit/f392fd28922800fe015e69d51d8787eb02aeed6a))
* **ext-openapi:** pick the page that reads the document ([945326a](https://github.com/Relism/Flash/commit/945326ac926d5240fbad9f6753743f55d1ec9d6a))
* **ext-protobuf-java:** binary Protocol Buffer bodies ([a1283cf](https://github.com/Relism/Flash/commit/a1283cf6355d4864851c8d2fac969dbdbe5c0fd0))
* **ext-scheduler:** add interval and cron background jobs ([7e07734](https://github.com/Relism/Flash/commit/7e0773450c65f0524e3a6144ad0c058dd86014c7))
* **ext-security:** add an OAuth 2.1 authorization server ([c9c502c](https://github.com/Relism/Flash/commit/c9c502c44de8a58391204ce2854257533a8519b5))
* **ext-validation-protovalidate:** Protovalidate rules for protobuf bodies ([c07f008](https://github.com/Relism/Flash/commit/c07f008e2fd534476a25d40b7ecb778a2013d099))
* **ext-validation:** add request validation with compiled constraints ([84a591e](https://github.com/Relism/Flash/commit/84a591e946b5f4c3ff3facc864ee14972a9a04de))
* **ext-validation:** codec families of validation engines ([3c45584](https://github.com/Relism/Flash/commit/3c455849c6219a41e6f1ba0ec795d5f6011fc1a6))
* **ext-vite:** gzip at build time, fall back to the index only for navigations ([f94ce50](https://github.com/Relism/Flash/commit/f94ce506f27928bd5b9d8a3c556c7f5237cab572))
* **ext-vite:** recognise navigations by Sec-Fetch-Mode, read every header in place ([4feea45](https://github.com/Relism/Flash/commit/4feea45cc02cdc2d0af01cc55828e93b8b8b1d08))
* **ext-vite:** replace the web bundler with a Vite extension and its Maven plugin ([adba3f5](https://github.com/Relism/Flash/commit/adba3f5a2532d7f4d9fc3a37c00c3158572c2a53))
* **ext-web-bundler:** add build-time asset scanning and static-frontend strategy ([8c7f8f0](https://github.com/Relism/Flash/commit/8c7f8f02fed22959ca2715c055adcf8936588f13))
* introduce WebSocket support with new endpoints and transaction propagation enhancements ([724a560](https://github.com/Relism/Flash/commit/724a560180150bad221990097e13400c4fdc9b38))
* name both ends of a handler, and let an engine own validation ([68bfba2](https://github.com/Relism/Flash/commit/68bfba23d388fa505c7b98fce89f171ae6a8fa8b))
* **testing:** boot lazily instead of eagerly in beforeEach ([ea6b0ef](https://github.com/Relism/Flash/commit/ea6b0ef899d06be7b8a340103f63d65c72ed1ed9))
* **testing:** support reusable request customizers ([f117555](https://github.com/Relism/Flash/commit/f117555068e31cb33648b29fde8f5641bb5b96c0))


### Bug Fixes

* **build:** don't skip deploying flash-extensions' own POM ([5cc9186](https://github.com/Relism/Flash/commit/5cc91861ec78f54e85e4e08074fe75d5dc254864))
* **build:** resolve fpr-core from Gitea, not the dead maven.relism.dev ([130963b](https://github.com/Relism/Flash/commit/130963bfc6985efb2100ba7396fa17ccc27aad95))
* **ci:** correct javadoc publish path and maven settings schema ([84e469a](https://github.com/Relism/Flash/commit/84e469a3542b03bab126cf878f5a8127b407533e))
* **ci:** publish versioned javadocs only on gh-pages ([69569a0](https://github.com/Relism/Flash/commit/69569a08a670d8f85ebc43226d9e78cccdc74ccb))
* **core:** fix HTTP/2 rate-limiter false positives, connection-flood OOM, and a streaming-body leak ([170ff53](https://github.com/Relism/Flash/commit/170ff53f1ee46df32677f961933cbcc9413f5898))
* **core:** honour HttpException status in the default exception handler ([8b37566](https://github.com/Relism/Flash/commit/8b37566e13a54aa777638a46dda60c27664b7b0e))
* **core:** reassemble fragmented websocket messages and validate their UTF-8 ([c241f73](https://github.com/Relism/Flash/commit/c241f73f418adb20da648a7aa7a4652c934677d8))
* **core:** stabilize streaming and transaction lifecycle ([35736ef](https://github.com/Relism/Flash/commit/35736ef23c8b1a3bb96479b9676a10c98705ca49))
* **data:** fire transaction synchronizations, and scope them to their transaction ([42e0e15](https://github.com/Relism/Flash/commit/42e0e15e5bb98b0d907cd9f36c9250c5dd46cb06))
* **ext-jackson:** a constraint says what it wants in its own words ([c024570](https://github.com/Relism/Flash/commit/c024570d6405cd106f1b8ee87c5a9ce52be860a8))
* **ext-openapi:** allow contributors to enrich default responses ([2542f1e](https://github.com/Relism/Flash/commit/2542f1e58d487b309af83247833f6c4ead52f420))
* **ext-openapi:** an answer's media type is the handler's, not the body's ([8602aa2](https://github.com/Relism/Flash/commit/8602aa2c772060a2b2cab79c1a5a18ddb7d22140))
* **ext-openapi:** one shared answer per status, not a numbered family ([ec28beb](https://github.com/Relism/Flash/commit/ec28beb1d2e5ea86bd0e14ced5f3326697f8e261))
* **ext-view:** clear cross-engine error, propagate globals across JteExtension builder chain ([0ef477c](https://github.com/Relism/Flash/commit/0ef477c90080b438ce7b2c238e22bee9a55ee454))
* **ext:** update stale HeaderMap references to Http1HeaderMap ([f31517f](https://github.com/Relism/Flash/commit/f31517f1c883011e35611672c8366606a3e501fa))
* **http2:** close a streaming response body on every exit path ([be57434](https://github.com/Relism/Flash/commit/be574347401eeff4cdf4872126bfb2e9e9f82113))
* **websocket:** mask outgoing CLOSE frames in client mode ([68d01a0](https://github.com/Relism/Flash/commit/68d01a050893172ce2ce4c83f6c0e78d04f1724b))


### Documentation

* **core:** document HTTP/2 operation and architecture ([afac733](https://github.com/Relism/Flash/commit/afac7333beaf563cc8c234c3e6893f3d0a68f353))
* **data:** write the data-layer docs in English ([08aecab](https://github.com/Relism/Flash/commit/08aecabbdf0ef3d52bd2f1c5fd5df45dac739c87))
* **testing:** document flash-testing and the limits it deliberately keeps ([4bce883](https://github.com/Relism/Flash/commit/4bce8834819d75d77e9c4030be67c08df9cd0ea7))
