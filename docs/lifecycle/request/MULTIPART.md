# Multipart

`Multipart` is a lazy streaming `multipart/form-data` parser. It reads from `req.body().stream()`
— the request body is **never fully materialised**. Text fields are buffered eagerly (they are
small by definition); file part bodies are exposed as zero-copy `InputStream`s backed directly
by the socket.

---

## Creating a parser

```java
Multipart mp = Multipart.of(req);
```

Throws `IllegalArgumentException` if the request is not `multipart/form-data` or the
`boundary` parameter is missing. Throws `IOException` if the initial stream read fails (the
parser skips the opening `--boundary\r\n` preamble on construction).

---

## Reading text fields — `field(String name)`

```java
String userId = mp.field("userId");  // null if absent or a file part
String width  = mp.field("width");
```

Scans forward through the stream until the named text field is found. Text parts encountered
along the way are buffered eagerly. File bodies encountered along the way are **drained
silently** (discarded without heap allocation).

**Order-independent for text:** once a text field has been scanned, it is cached. Calling
`mp.field("userId")` after `mp.field("width")` will hit the cache if `userId` was encountered
first in the stream.

Returns `null` if the field is absent or if the named part is a file (has a `filename`
attribute). Never returns file content.

---

## Reading file parts — `file(String name)`

```java
Part avatar = mp.file("avatar");  // null if absent or a text field
```

Scans forward until the named file part is found. Text parts encountered along the way are
buffered (accessible later via `field()`). Earlier file bodies encountered along the way are
drained silently.

The returned `Part`'s stream is backed by the socket. **Consume it before calling any other
scan method** — calling `mp.field()`, `mp.file()`, or `mp.parts()` will drain the active
stream automatically, but any bytes already read are gone.

Returns `null` if absent or if the named part is a text field.

---

## Collecting multiple parts — `parts(String name)` and `parts()`

```java
List<Part> files = mp.parts("files");  // all parts named "files"
List<Part> all   = mp.parts();         // every part in declaration order
```

Forces a **full scan**. All file bodies are materialised into `byte[]` arrays — accept this
heap cost explicitly when calling these methods. Useful for bulk uploads where you need all
files before processing any of them.

`parts()` must be called before any `file()` call if you need all file parts, since a previous
`file()` call may have consumed some of them from the stream.

---

## The `Part` object

Every part — whether text or file — is returned as a `Part`.

### Metadata

```java
part.name()         // field name from Content-Disposition
part.filename()     // original filename, null for text fields
part.contentType()  // declared Content-Type, null if not present
part.isFile()       // true when filename() != null
```

### Body access

```java
// Zero-copy stream — the default
InputStream in = part.stream();

// Explicit materialisation — opt-in heap allocation
byte[] data = part.materialize();

// UTF-8 convenience — materialises if needed, result is cached
String text = part.text();
```

#### `stream()`

For **buffered parts** (text fields and `parts()` results): returns a fresh
`ByteArrayInputStream` over the cached `byte[]`. Repeatable, no I/O.

For **streaming file parts** (from `file()`): returns the raw bounded socket `InputStream`.
**Read it once only**, and before requesting the next part.

#### `materialize()`

Reads the full part body into a `byte[]` and caches it. Safe to call multiple times on
buffered parts (returns the same array). For streaming file parts, triggers a full socket read
on the first call; subsequent calls return the cached array.

#### `text()`

Convenience over `materialize()`. Decodes the body as UTF-8. Result is cached.

---

## Memory model

| Operation | Heap cost |
|---|---|
| `Multipart.of(req)` | 8 KB window buffer + boundary bytes |
| `mp.field("x")` | body of the text field only (~bytes of the value) |
| `mp.file("f")` | none — body is a bounded socket stream |
| `part.stream()` on a file | none |
| `part.materialize()` on a file | full file size |
| `mp.parts()` | full body of every part |
| Draining a file body (implicit) | 8 KB reused drain buffer |

---

## Ordering constraints and safe patterns

### Pattern 1 — text fields + one file (most common)

Text fields are accessible in any order. The file is streamed zero-copy.

```java
Multipart mp     = Multipart.of(req);
String    userId = mp.field("userId");   // order doesn't matter for text
String    label  = mp.field("label");
Part      file   = mp.file("file");      // stream it after fields

Files.copy(file.stream(), destination, StandardCopyOption.REPLACE_EXISTING);
return res.body("saved " + file.filename());
```

### Pattern 2 — echo large file (zero heap)

```java
Part file = Multipart.of(req).file("file");
return res.chunked(file.stream());  // socket-in → chunked-out, never touches heap
```

### Pattern 3 — bulk upload (accept the heap cost)

```java
List<Part> files = Multipart.of(req).parts("files");
for (Part f : files) {
    Files.write(dir.resolve(f.filename()), f.materialize());
}
```

### Pattern 4 — mixed order (file before text in the form)

If the form declares the file before a text field and you need the text first,
`field()` will drain the file body silently to reach the text part. The file is then gone.
Either reorder the form fields or use `parts()` if you need both.

```java
// Form order: file, then userId
// This works — file is drained silently, userId is buffered
String userId = mp.field("userId");

// But now mp.file("file") returns null — already drained
```

If you need both, use `parts()` upfront:

```java
List<Part> all    = mp.parts();
Part       file   = all.stream().filter(Part::isFile).findFirst().orElse(null);
String     userId = all.stream().filter(p -> "userId".equals(p.name())).findFirst()
                       .map(p -> { try { return p.text(); } catch (IOException e) { throw new UncheckedIOException(e); } })
                       .orElse(null);
```
