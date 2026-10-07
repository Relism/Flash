# Documentation page

`OpenApiModule` serves a page at `<basePath>/docs` that renders the JSON document. The page is built
once at boot and served as bytes.

```java
new OpenApiModule("/openapi", "My API", "1.0.0")
        .ui(Ui.scalar().theme(Ui.Scalar.Theme.DEEP_SPACE).darkMode(true));
```

| Factory | UI |
|---|---|
| `Ui.swagger()` | Swagger UI. The default |
| `Ui.redoc()` | Redoc |
| `Ui.scalar()` | Scalar |
| `Ui.none()` | No page. The JSON and YAML routes remain |

The bundle is loaded in the browser from jsDelivr at a pinned version (`swagger-ui-dist` 5.33.0,
`redoc` 2.5.4, `@scalar/api-reference` 1.71.0).

## Options

Named options use the bundles' own names, which land verbatim in the page's configuration.

| UI | Options |
|---|---|
| Swagger | `docExpansion(LIST, FULL, NONE)`, `modelsExpandDepth`, `deepLinking`, `filter`, `tryItOut`, `persistAuthorization`, `syntaxTheme`, `sortAlphabetically` |
| Redoc | `hideDownloadButton`, `disableSearch`, `requiredPropsFirst`, `sortPropsAlphabetically`, `jsonSampleExpandLevel`, `hideSchemaTitles`, `pathInMiddlePanel`, `hideHostname`, `nativeScrollbars`, `menuToggle` |
| Scalar | `theme`, `layout(MODERN, CLASSIC)`, `darkMode`, `hideDarkModeToggle`, `hideModels`, `hideSearch`, `hideTestRequestButton`, `hideClientButton`, `showSidebar`, `defaultOpenAllTags`, `sortOperationsBy(ALPHA, METHOD)` |

Three methods apply to all of them:

| Method | Effect |
|---|---|
| `option(name, value)` | Sets any option the class does not name. A `Map` value passes a nested object, for example Redoc's `theme`. `null` removes it |
| `customCss(css)` | Appended to the page's stylesheet |
| `cdn(url)` | Loads the bundle from another base URL: a mirror, a proxy, or files your app serves |

The page title is the module's `title`.
