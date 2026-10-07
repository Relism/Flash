# Using Thymeleaf

## Templates and fragments

| Annotation | Renders |
|---|---|
| `@Template("user")` | The whole template `/templates/user.html` |
| `@Fragment(template = "user", value = "card")` | Only the fragment `card` of that template. Without `value`, the module's default fragment, `content` |

Both are inherited. A class with both, or with a blank template name, fails the boot.

## Globals

Globals are placed under the name `global`: `${global.user}`. A model key called `global` fails the
render, because it is reserved. If no global is registered, nothing is added.

## Links

A link builder named `flash` handles `@{...}` expressions: path variables are filled from the
parameters and the rest go in the query string, all URL-encoded.

```html
<a th:href="@{/users/{id}(id=${user.id},page=${page})}">Profile</a>   <!-- /users/7?page=2 -->
```

## Settings

| Method | Default | Meaning |
|---|---|---|
| `prefix(String)` | `/templates/` | Classpath location of templates. A trailing `/` is added |
| `suffix(String)` | `.html` | What every template name ends with. A leading `.` is added |
| `mode(TemplateMode)` | `HTML` | Thymeleaf's template mode |
| `cache(boolean)` | `true` outside dev, `false` in dev | Whether parsed templates are kept |
| `defaultFragment(String)` | `content` | The fragment a `@Fragment` without a value renders |
| `global(key, resolver)` | none | See above |

Output is UTF-8 with `Content-Type: text/html`.
