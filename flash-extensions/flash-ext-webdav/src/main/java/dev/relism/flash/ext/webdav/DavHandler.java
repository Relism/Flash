package dev.relism.flash.ext.webdav;

import dev.relism.flash.ext.webdav.internal.Locks;
import dev.relism.flash.ext.webdav.internal.Locks.Lock;
import dev.relism.flash.ext.webdav.internal.OwnSpace;
import dev.relism.flash.ext.webdav.internal.Space;
import dev.relism.flash.ext.webdav.Xml.Element;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.HttpStatus;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.Response;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.namespace.QName;
import lombok.extern.slf4j.Slf4j;

/**
 * A WebDAV mount (RFC 4918, classes 1 and 2). The handler speaks the protocol: methods, properties,
 * locks, the {@code If} header, conditional and range requests, the quota (RFC 4331). It serves each
 * request from the {@link DavStorage} {@link #open} returns.
 *
 * <p>An engine extends it once and implements {@code open} ({@link dev.relism.flash.ext.webdav.nio.NioDavHandler} for a directory);
 * an application extends the engine's handler. Both can override:
 * <ul>
 *   <li>{@link #quota} and {@link #writable}, what the caller may do;</li>
 *   <li>one method per storage operation ({@link #read}, {@link #delete}, ...), called with the
 *       request each time the handler uses the storage. Override one to refuse, log or change the
 *       operation, and call {@code super} to perform it.</li>
 * </ul>
 */
@Slf4j
public abstract class DavHandler extends RequestHandler {

    // -- What the caller gets -----------------------------------------------------

    /** The storage this request is served from. Called once per request. */
    protected abstract DavStorage open(Request req) throws IOException;

    /** The bytes the storage may hold for this request; unlimited unless overridden. */
    protected long quota(Request req) throws IOException {
        return Long.MAX_VALUE;
    }

    /** Whether this request may change anything. A storage that is not {@link DavStorage.Writable} never can. */
    protected boolean writable(Request req) throws IOException {
        return true;
    }

    // -- Each storage operation, with the request -----------------------------------

    /** Every lookup of a resource: the target of a request, its parent, a {@code Destination}, an {@code If} resource. */
    protected Optional<DavNode> stat(Request req, DavPath path) throws IOException {
        return storage().stat(path);
    }

    /** The members of a collection: {@code PROPFIND} with {@code Depth: 1}, {@code GET} on a collection, and each level of a deep {@code DELETE} or {@code COPY}. */
    protected List<DavNode> list(Request req, DavPath collection) throws IOException {
        return storage().list(collection);
    }

    /** The bytes of a {@code GET}. */
    protected InputStream read(Request req, DavPath file, long offset, long length) throws IOException {
        return storage().read(file, offset, length);
    }

    /** A {@code PUT}, and the empty file a {@code LOCK} on a free path creates. */
    protected void write(Request req, DavPath file, InputStream body, OptionalLong length) throws IOException {
        writer().write(file, body, length);
    }

    /** A {@code MKCOL}. */
    protected void mkdir(Request req, DavPath collection) throws IOException {
        writer().mkdir(collection);
    }

    /** Each resource a {@code DELETE} removes, members first, and what a {@code COPY} or {@code MOVE} replaces. */
    protected void delete(Request req, DavPath path) throws IOException {
        writer().delete(path);
    }

    /** Each resource a {@code COPY} copies, collections before their members. */
    protected void copy(Request req, DavPath from, DavPath to) throws IOException {
        writer().copy(from, to);
    }

    /** A {@code MOVE}, once for the whole tree. */
    protected void move(Request req, DavPath from, DavPath to) throws IOException {
        writer().move(from, to);
    }

    /** The properties clients set on a resource; in memory when the storage does not keep them. */
    protected Map<QName, String> properties(Request req, DavPath path) throws IOException {
        DavStorage storage = storage();
        if (storage instanceof DavStorage.Properties kept) return kept.properties(path);
        return memory(storage).getOrDefault(path, Map.of());
    }

    /** A {@code PROPPATCH}, and the properties a {@code COPY} carries. */
    protected void properties(Request req, DavPath path, Map<QName, String> properties) throws IOException {
        DavStorage storage = storage();
        if (storage instanceof DavStorage.Properties kept) {
            kept.properties(path, properties);
            return;
        }
        int size = 0;
        for (Map.Entry<QName, String> property : properties.entrySet())
            size += Xml.clark(property.getKey()).length() + property.getValue().length();
        if (size > MAX_MEMORY_PROPERTIES)
            throw new HttpException(507, "dead properties kept in memory are limited to " + MAX_MEMORY_PROPERTIES + " characters a resource");
        if (!inMemory) {
            inMemory = true;
            log.info("{} keeps dead properties of {} in memory: they are lost when the app stops", getClass().getSimpleName(),
                    storage.getClass().getSimpleName());
        }
        if (properties.isEmpty()) memory(storage).remove(path);
        else memory(storage).put(path, Map.copyOf(properties));
    }

    // -- The protocol ------------------------------------------------------------

    private static final ThreadLocal<DavStorage> STORAGE = new ThreadLocal<>();
    private static final int MAX_MEMORY_PROPERTIES = 64 * 1024;
    /** What an upload of unknown length takes from the quota at a time. */
    private static final long BLOCK = 1024 * 1024;
    private static final String XML = "application/xml; charset=utf-8";
    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private static final List<String> READS = List.of("OPTIONS", "GET", "HEAD", "PROPFIND");
    private static final List<String> WRITES = List.of("PUT", "PROPPATCH", "COPY", "LOCK", "UNLOCK", "MKCOL", "DELETE", "MOVE");
    private static final String READ_ONLY = String.join(", ", READS);
    private static final String READ_WRITE = READ_ONLY + ", " + String.join(", ", WRITES);
    private static final List<QName> ALL_PROPERTIES = java.util.stream.Stream.of("creationdate", "getcontentlength",
            "getcontenttype", "getetag", "getlastmodified", "resourcetype", "supportedlock", "lockdiscovery")
            .map(name -> new QName(Xml.DAV, name)).toList();
    private static final QName LOCK_DISCOVERY = new QName(Xml.DAV, "lockdiscovery");
    private static final List<QName> QUOTA = List.of(new QName(Xml.DAV, "quota-available-bytes"), new QName(Xml.DAV, "quota-used-bytes"));
    private static final Set<QName> LIVE = Set.copyOf(java.util.stream.Stream.concat(ALL_PROPERTIES.stream(), QUOTA.stream()).toList());
    private static final String STATUS_200 = status(200);
    private static final String STATUS_404 = status(404);
    private static final String SUPPORTED_LOCKS = "<D:supportedlock>"
            + "<D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry>"
            + "<D:lockentry><D:lockscope><D:shared/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry>"
            + "</D:supportedlock>";

    private DavModule module;
    private volatile boolean inMemory;

    /** One request: where the mount is, the path below it, its {@code If} header, its storage and that storage's space. */
    private final class Target {
        final Request req;
        final Response res;
        final String mount;
        final DavPath path;
        final IfHeader conditions;
        final DavStorage storage;
        final Space space;
        final boolean writes;
        private Long limit;

        Target(Request req, Response res, String mount, DavPath path, IfHeader conditions, DavStorage storage, boolean writes) {
            this.req = req;
            this.res = res;
            this.mount = mount;
            this.path = path;
            this.conditions = conditions;
            this.storage = storage;
            this.space = space(storage);
            this.writes = writes;
        }

        String allow() {
            return writes ? READ_WRITE : READ_ONLY;
        }

        /** The quota, asked once per request and only when needed, before the space is held. */
        long limit() throws IOException {
            if (limit == null) limit = Math.max(0, quota(req));
            return limit;
        }
    }

    /** A refusal RFC 4918 explains with a precondition element in a {@code DAV:error} body. */
    private static final class Refusal extends RuntimeException {
        final int status;
        final String condition;

        Refusal(int status, String condition) {
            super(condition, null, false, false);
            this.status = status;
            this.condition = condition;
        }
    }

    private record Failure(DavPath path, boolean collection, int status) {}

    private record Propfind(boolean names, boolean all, List<QName> properties) {}

    private record Member(DavPath path, DavNode node, Map<QName, String> dead, int unreadable) {}

    private record Quota(long available, long used) {}

    /** Where the locks and bytes in use of a storage are kept: in this process's memory, unless the storage keeps its own. */
    private Space space(DavStorage storage) {
        Space own = storage instanceof OwnSpace kept ? kept.space() : null;
        return own != null ? own : module.space(storage);
    }

    @Override
    protected final void onInit() {
        module = services().find(DavModule.class).orElseThrow(() ->
                new IllegalStateException(getClass().getSimpleName() + " is a DavHandler: install new DavModule()"));
    }

    @Override
    protected final Object handle(Request req, Response res) throws Exception {
        String rest = req.param("**");
        if (rest == null) throw new IllegalStateException(getClass().getSimpleName() + " is routed without a trailing /**");
        String mount = req.path().substring(0, req.path().length() - rest.length());
        while (mount.endsWith("/")) mount = mount.substring(0, mount.length() - 1);
        String method = req.requestLine().getMethodName();
        boolean read = READS.contains(method);
        if (!read && !WRITES.contains(method) && req.method() == null)
            throw new HttpException(501, method + " is not a WebDAV method this server implements");
        DavStorage storage = open(req);
        boolean writes = storage instanceof DavStorage.Writable && writable(req);
        if (!read && !(writes && WRITES.contains(method))) throw HttpException.methodNotAllowed(writes ? READ_WRITE : READ_ONLY);
        String conditions = req.header("If");
        Target t = new Target(req, res, mount, DavPath.parse(rest), conditions == null ? null : IfHeader.parse(conditions), storage, writes);
        STORAGE.set(storage);
        try {
            return switch (method) {
                case "OPTIONS" -> options(t);
                case "GET" -> get(t, false);
                case "HEAD" -> get(t, true);
                case "PROPFIND" -> propfind(t);
                case "PUT" -> put(t);
                case "PROPPATCH" -> proppatch(t);
                case "MKCOL" -> mkcol(t);
                case "DELETE" -> delete(t);
                case "COPY" -> copyOrMove(t, false);
                case "MOVE" -> copyOrMove(t, true);
                case "LOCK" -> lock(t);
                default -> unlock(t);
            };
        } catch (Refusal refused) {
            return refuse(res, refused);
        } catch (IOException | UnsupportedOperationException failure) {
            String full = full(failure);
            if (full != null) return refuse(res, new Refusal(507, full));
            int status = status(failure);
            if (status == 500) throw failure;
            throw new HttpException(status, failure.getMessage() == null ? "" : failure.getMessage());
        } finally {
            STORAGE.remove();
        }
    }

    private Object options(Target t) throws IOException {
        check(t);
        t.res.status(200).header("DAV", t.writes ? "1, 2" : "1").header("MS-Author-Via", "DAV").header("Allow", t.allow());
        return null;
    }

    private Object get(Target t, boolean head) throws IOException {
        DavNode node = existing(t, t.path);
        check(t);
        Request req = t.req;
        Response res = t.res;
        if (node.collection()) {
            StringBuilder page = new StringBuilder("<!doctype html><meta charset=\"utf-8\"><title>")
                    .append(Xml.escape(t.path.toString())).append("</title><ul>");
            for (DavNode child : list(req, t.path))
                page.append("<li><a href=\"").append(Xml.escape(t.path.child(child.name()).href(t.mount, child.collection())))
                        .append("\">").append(Xml.escape(child.name())).append(child.collection() ? "/" : "").append("</a>");
            res.type("text/html; charset=utf-8");
            return page.append("</ul>").toString();
        }
        res.header("ETag", node.etag()).header("Last-Modified", HTTP_DATE.format(node.modified())).header("Accept-Ranges", "bytes");
        if (node.contentType() != null) res.type(node.contentType());
        int precondition = preconditions(req, node, true);
        if (precondition != 0) {
            res.status(precondition);
            return null;
        }
        long length = node.length();
        long start = 0;
        long count = length;
        String range = req.header("Range");
        if (range != null && rangeApplies(req.header("If-Range"), node)) {
            long[] bounds = range(range, length);
            if (bounds != null && bounds.length == 0) {
                res.status(416).header("Content-Range", "bytes */" + length);
                return null;
            }
            if (bounds != null) {
                start = bounds[0];
                count = bounds[1] - bounds[0] + 1;
                res.status(206).header("Content-Range", "bytes " + bounds[0] + "-" + bounds[1] + "/" + length);
            }
        }
        res.stream(head ? InputStream.nullInputStream() : read(req, t.path, start, count), count);
        return null;
    }

    private Object put(Target t) throws IOException {
        if (t.req.header("Content-Range") != null) throw HttpException.badRequest("a partial PUT (Content-Range) is not supported");
        long declared = t.req.body().contentLength();
        String expected = t.req.header("X-Expected-Entity-Length"); // Finder, with a chunked body
        if (declared < 0 && expected != null) declared = Long.parseLong(expected.strip());
        Upload body = new Upload(t, t.limit());
        try {
            try (Space space = t.space.enter()) {
                body.node = admit(t);
                space.begin();
                body.begun = true;
                if (declared > 0 && body.limit != Long.MAX_VALUE) body.take(space, declared, declared);
            }
            write(t.req, t.path, body, declared < 0 ? OptionalLong.empty() : OptionalLong.of(declared));
        } finally {
            body.finish();
        }
        DavNode written = node(t, t.path);
        if (written != null) t.res.header("ETag", written.etag());
        t.res.status(body.node == null ? 201 : 204);
        return null;
    }

    /** What a {@code PUT} must satisfy, when it starts and again once its body has arrived; the resource it replaces. */
    private DavNode admit(Target t) throws IOException {
        DavNode node = node(t, t.path);
        if (node != null && node.collection()) throw HttpException.methodNotAllowed(t.allow());
        requireParent(t, t.path);
        guard(t, required(t, t.path, node == null, false));
        if (preconditions(t.req, node, false) != 0) throw new HttpException(412, "the precondition does not hold");
        return node;
    }

    /**
     * The body of a {@code PUT} as the storage reads it. Under a quota it takes its bytes from the
     * space before the storage gets them: all at once when the length is known, a block at a time
     * otherwise. At its end it holds the space and checks the request again, so the new content
     * replaces the old only while the request may still write it; the space stays held until
     * {@link #finish}, after the write.
     */
    private final class Upload extends FilterInputStream {
        private final Target t;
        final long limit;
        DavNode node;
        boolean begun;
        private long taken;
        private long received;
        private Space held;
        private long before = -1;

        Upload(Target t, long limit) {
            super(t.req.body().stream());
            this.t = t;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            arrived(b < 0 ? -1 : 1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            arrived(n);
            return n;
        }

        private void arrived(int n) throws IOException {
            if (n < 0) {
                if (held == null) ended();
                return;
            }
            received += n;
            if (received > taken && limit != Long.MAX_VALUE) try (Space space = t.space.enter()) {
                take(space, Math.max(received - taken, BLOCK), received - taken);
            }
        }

        /** Takes {@code wanted} bytes, or only the {@code needed} ones when those do not fit, after giving back what dead processes held. */
        void take(Space space, long wanted, long needed) throws IOException {
            if (space.reserve(wanted, limit)) taken += wanted;
            else if (space.reserve(needed, limit) || space.recover() && space.reserve(needed, limit)) taken += needed;
            else throw quotaExceeded();
        }

        private void ended() throws IOException {
            held = t.space.enter();
            before = stored(t);
            node = admit(t);
        }

        /** Counts what the write changed and gives back what the upload took. */
        void finish() throws IOException {
            if (!begun) return;
            Space space = held != null ? held : t.space.enter();
            try {
                if (before >= 0) space.settle(stored(t) - before);
                space.release(taken);
                space.end();
            } finally {
                space.close();
            }
        }
    }

    private Object mkcol(Target t) throws IOException {
        long declared = t.req.body().contentLength();
        if (declared > 0 || declared < 0 && t.req.body().stream().read() >= 0)
            throw new HttpException(415, "MKCOL takes no body");
        try (Space held = t.space.enter()) {
            if (node(t, t.path) != null) throw HttpException.methodNotAllowed(t.allow());
            requireParent(t, t.path);
            guard(t, required(t, t.path, true, false));
            mkdir(t.req, t.path);
        }
        t.res.status(201);
        return null;
    }

    private Object delete(Target t) throws IOException {
        List<Failure> failures = new ArrayList<>();
        try (Space space = t.space.enter()) {
            DavNode node = existing(t, t.path);
            if (t.path.isRoot()) throw HttpException.forbidden("the mount itself cannot be deleted");
            guard(t, required(t, t.path, true, true));
            space.begin();
            try {
                remove(t, space, t.path, node, failures);
            } finally {
                space.end();
            }
        }
        if (!failures.isEmpty()) return failures(t, failures, t.path);
        t.res.status(204);
        return null;
    }

    /**
     * Both check and act while the space is held, but a copy runs without it, its bytes taken up
     * front: a large copy does not stop the other requests of the space.
     */
    private Object copyOrMove(Target t, boolean move) throws IOException {
        String header = t.req.header("Destination");
        if (header == null) throw HttpException.badRequest("Destination is required");
        DavPath destination = resolve(t, header.strip());
        if (destination == null) throw new HttpException(502, "the destination is not in this mount");
        if (destination.equals(t.path)) throw HttpException.forbidden("the source and the destination are the same");
        if (t.path.contains(destination)) throw HttpException.forbidden("a collection cannot go inside itself");
        if (move && t.path.isRoot() || destination.isRoot()) throw HttpException.forbidden("the mount itself cannot be replaced or moved");
        String depth = t.req.header("Depth");
        boolean deep = depth == null || depth.equalsIgnoreCase("infinity");
        String overwrite = t.req.header("Overwrite");
        if (overwrite != null && !overwrite.equals("T") && !overwrite.equals("F")) throw HttpException.badRequest("Overwrite is T or F");
        long limit = move ? Long.MAX_VALUE : t.limit();
        List<Failure> failures = new ArrayList<>();
        DavNode node;
        DavNode existing;
        long added = 0;
        boolean copying = false;
        try (Space space = t.space.enter()) {
            node = existing(t, t.path);
            if (!deep && (!depth.equals("0") || move && node.collection()))
                throw HttpException.badRequest("invalid Depth for " + (move ? "MOVE" : "COPY") + ": " + depth);
            existing = node(t, destination);
            if (existing != null && "F".equals(overwrite)) throw new HttpException(412, "the destination exists and Overwrite is F");
            requireParent(t, destination);
            List<Lock> required = required(t, destination, true, existing != null);
            if (move) required.addAll(required(t, t.path, true, true));
            guard(t, required);
            if (limit != Long.MAX_VALUE) {
                added = node.collection() ? deep ? t.storage.usage(t.path) : 0 : node.length();
                long freed = existing == null ? 0 : existing.collection() ? t.storage.usage(destination) : existing.length();
                if (added > 0 && space.used() - freed + added > limit) throw quotaExceeded();
            }
            space.begin();
            try {
                if (existing != null) remove(t, space, destination, existing, failures);
                if (!failures.isEmpty()) return failures(t, failures, destination);
                if (move) moved(t, space, destination);
                else if (added > 0 && !space.reserve(added, limit)) throw quotaExceeded();
                else copying = true;
            } finally {
                if (!copying) space.end();
            }
        }
        if (copying) try {
            duplicate(t, t.path, node, destination, deep, failures);
        } finally {
            try (Space space = t.space.enter()) {
                space.release(added);
                space.end();
            }
        }
        if (!failures.isEmpty()) return failures(t, failures, destination);
        t.res.status(existing == null ? 201 : 204);
        return null;
    }

    private void moved(Target t, Space space, DavPath destination) throws IOException {
        move(t.req, t.path, destination);
        if (!(t.storage instanceof DavStorage.Properties)) {
            Map<DavPath, Map<QName, String>> memory = memory(t.storage);
            for (DavPath held : List.copyOf(memory.keySet()))
                if (t.path.contains(held)) memory.put(held.moved(t.path, destination), memory.remove(held));
        }
        space.locks().releaseWithin(t.path);
    }

    private Object propfind(Target t) throws IOException {
        String depth = t.req.header("Depth");
        if (depth == null || depth.equalsIgnoreCase("infinity")) throw new Refusal(403, "<D:propfind-finite-depth/>");
        if (!depth.equals("0") && !depth.equals("1")) throw HttpException.badRequest("invalid Depth: " + depth);
        Propfind find = propfind(Xml.read(t.req));
        boolean discovering = !find.names && (find.all || find.properties.contains(LOCK_DISCOVERY));
        boolean counting = find.properties.stream().anyMatch(QUOTA::contains);
        long limit = counting ? t.limit() : Long.MAX_VALUE;
        DavNode node;
        List<Lock> locks = List.of();
        Quota quota = null;
        if (t.conditions == null && !discovering && !counting) node = existing(t, t.path);
        else try (Space space = t.space.enter()) {
            node = existing(t, t.path);
            guard(t, List.of());
            if (discovering) locks = space.locks().all();
            if (counting) quota = quota(t, space, limit);
        }
        // everything the storage and the subclass answer is read here, on the request's thread; the answer is only written below
        List<Member> members = new ArrayList<>();
        members.add(member(t, t.path, node));
        if (depth.equals("1") && node.collection())
            for (DavNode child : list(t.req, t.path)) members.add(member(t, t.path.child(child.name()), child));
        Quota reported = quota;
        List<Lock> held = locks;
        t.res.status(207).type(XML).streaming(out -> {
            Xml.Writer writer = new Xml.Writer(out);
            writer.raw(HEAD + "<D:multistatus xmlns:D=\"DAV:\">");
            for (Member member : members) describe(writer, t, member, find, reported, held);
            writer.raw("</D:multistatus>").flush();
        });
        return null;
    }

    private static Propfind propfind(Element body) {
        if (body == null) return new Propfind(false, true, List.of());
        if (!body.is(Xml.DAV, "propfind")) throw HttpException.badRequest("the body is not a DAV:propfind");
        Element prop = body.child(Xml.DAV, "prop");
        Element include = body.child(Xml.DAV, "include");
        if (body.child(Xml.DAV, "propname") != null) return new Propfind(true, false, List.of());
        if (body.child(Xml.DAV, "allprop") != null) return new Propfind(false, true, include == null ? List.of() : names(include));
        if (prop != null) return new Propfind(false, false, names(prop));
        throw HttpException.badRequest("DAV:propfind names nothing to find");
    }

    /**
     * A member with its dead properties. When they cannot be read (a directory the server may not
     * enter, say) it is still listed with its live properties; any asked for by name carry the status.
     */
    private Member member(Target t, DavPath path, DavNode node) {
        try {
            return new Member(path, node, properties(t.req, path), 0);
        } catch (IOException | RuntimeException failure) {
            return new Member(path, node, Map.of(), status(failure));
        }
    }

    private static void describe(Xml.Writer w, Target t, Member member, Propfind find, Quota quota, List<Lock> locks)
            throws IOException {
        List<QName> wanted = wanted(find, member);
        w.raw("<D:response><D:href>").text(member.path.href(t.mount, member.node.collection())).raw("</D:href>");
        boolean any = false;
        for (QName name : wanted) {
            if (!present(name, member, quota)) continue;
            if (!any) w.raw("<D:propstat><D:prop>");
            any = true;
            if (find.names) w.tag(name, false);
            else if (LIVE.contains(name)) live(w, name, t, member, quota, locks);
            else w.tag(name, true).raw(member.dead.get(name)).end(name);
        }
        if (any) w.raw("</D:prop>").raw(STATUS_200).raw("</D:propstat>");
        if (!find.names) absent(w, member, quota, wanted, false, STATUS_404);
        if (member.unreadable != 0) absent(w, member, quota, wanted, true, status(member.unreadable));
        w.raw("</D:response>");
    }

    /** The names to answer for, in order, each once. */
    private static List<QName> wanted(Propfind find, Member member) {
        if (!find.all && !find.names) return find.properties;
        List<QName> wanted = new ArrayList<>(ALL_PROPERTIES.size() + member.dead.size() + find.properties.size());
        wanted.addAll(ALL_PROPERTIES);
        wanted.addAll(member.dead.keySet());
        for (QName name : find.properties) if (!wanted.contains(name)) wanted.add(name);
        return wanted;
    }

    /**
     * One propstat of the wanted names that are not there: the dead ones the storage could not read
     * when {@code unreadable}, the rest otherwise.
     */
    private static void absent(Xml.Writer w, Member member, Quota quota, List<QName> wanted, boolean unreadable, String status)
            throws IOException {
        boolean any = false;
        for (QName name : wanted) {
            if (present(name, member, quota) || unreadable != (member.unreadable != 0 && !LIVE.contains(name))) continue;
            if (!any) w.raw("<D:propstat><D:prop>");
            any = true;
            w.tag(name, false);
        }
        if (any) w.raw("</D:prop>").raw(status).raw("</D:propstat>");
    }

    private static boolean present(QName name, Member member, Quota quota) {
        if (!LIVE.contains(name)) return member.dead.containsKey(name);
        DavNode node = member.node;
        return switch (name.getLocalPart()) {
            case "creationdate" -> node.created() != null;
            case "getcontentlength" -> !node.collection();
            case "getcontenttype" -> node.contentType() != null;
            case "getetag" -> node.etag() != null;
            case "getlastmodified" -> node.modified() != null;
            case "quota-available-bytes", "quota-used-bytes" -> quota != null;
            default -> true; // resourcetype, supportedlock, lockdiscovery
        };
    }

    /** A live property that is {@link #present}, as XML. */
    private static void live(Xml.Writer w, QName name, Target t, Member member, Quota quota, List<Lock> locks) throws IOException {
        DavNode node = member.node;
        switch (name.getLocalPart()) {
            case "creationdate" -> w.raw("<D:creationdate>")
                    .raw(DateTimeFormatter.ISO_INSTANT.format(node.created().truncatedTo(ChronoUnit.SECONDS))).raw("</D:creationdate>");
            case "getcontentlength" -> w.raw("<D:getcontentlength>").number(node.length()).raw("</D:getcontentlength>");
            case "getcontenttype" -> w.raw("<D:getcontenttype>").text(node.contentType()).raw("</D:getcontenttype>");
            case "getetag" -> w.raw("<D:getetag>").text(node.etag()).raw("</D:getetag>");
            case "getlastmodified" -> w.raw("<D:getlastmodified>").raw(HTTP_DATE.format(node.modified())).raw("</D:getlastmodified>");
            case "resourcetype" -> w.raw(node.collection() ? "<D:resourcetype><D:collection/></D:resourcetype>" : "<D:resourcetype/>");
            case "supportedlock" -> w.raw(t.writes ? SUPPORTED_LOCKS : "<D:supportedlock/>");
            case "lockdiscovery" -> {
                w.raw("<D:lockdiscovery>");
                for (Lock lock : locks) if (lock.covers(member.path)) w.raw(activelock(t, lock));
                w.raw("</D:lockdiscovery>");
            }
            case "quota-available-bytes" -> w.raw("<D:quota-available-bytes>").number(quota.available()).raw("</D:quota-available-bytes>");
            default -> w.raw("<D:quota-used-bytes>").number(quota.used()).raw("</D:quota-used-bytes>");
        }
    }

    private Object proppatch(Target t) throws IOException {
        Element update = Xml.read(t.req);
        if (update == null || !update.is(Xml.DAV, "propertyupdate")) throw HttpException.badRequest("the body is not a DAV:propertyupdate");
        Map<QName, Integer> outcome = new LinkedHashMap<>();
        try (Space held = t.space.enter()) {
            existing(t, t.path);
            guard(t, required(t, t.path, false, false));
            Map<QName, String> properties = new HashMap<>(properties(t.req, t.path));
            boolean protectedOnes = false;
            for (Element action : update.children()) {
                boolean set = action.is(Xml.DAV, "set");
                if (!set && !action.is(Xml.DAV, "remove") || action.child(Xml.DAV, "prop") == null) continue;
                for (Element property : action.child(Xml.DAV, "prop").children()) {
                    QName name = property.qname();
                    protectedOnes |= LIVE.contains(name);
                    outcome.put(name, LIVE.contains(name) ? 403 : 200);
                    if (set) properties.put(name, property.xml());
                    else properties.remove(name);
                }
            }
            if (outcome.isEmpty()) throw HttpException.badRequest("DAV:propertyupdate changes nothing");
            if (protectedOnes) outcome.replaceAll((name, status) -> status == 403 ? 403 : 424);
            else {
                int status = 200;
                try {
                    properties(t.req, t.path, properties);
                } catch (IOException | RuntimeException failure) {
                    status = status(failure) == 500 ? 507 : status(failure);
                }
                int written = status;
                outcome.replaceAll((name, ignored) -> written);
            }
        }
        StringBuilder answer = new StringBuilder(HEAD).append("<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>")
                .append(Xml.escape(t.path.href(t.mount, false))).append("</D:href>");
        for (int status : new TreeSet<>(outcome.values())) {
            answer.append("<D:propstat><D:prop>");
            outcome.forEach((name, each) -> { if (each == status) answer.append(Xml.tag(name, false)); });
            answer.append("</D:prop>").append(status(status)).append("</D:propstat>");
        }
        t.res.status(207).type(XML);
        return answer.append("</D:response></D:multistatus>").toString();
    }

    private Object lock(Target t) throws IOException {
        Element info = Xml.read(t.req);
        long seconds = timeout(t.req.header("Timeout"));
        if (info == null) return refresh(t, seconds);
        if (!info.is(Xml.DAV, "lockinfo")) throw HttpException.badRequest("the body is not a DAV:lockinfo");
        Element scope = info.child(Xml.DAV, "lockscope");
        Element type = info.child(Xml.DAV, "locktype");
        boolean exclusive = scope != null && scope.child(Xml.DAV, "exclusive") != null;
        if (scope == null || exclusive == (scope.child(Xml.DAV, "shared") != null) || type == null || type.child(Xml.DAV, "write") == null)
            throw HttpException.badRequest("a lock is an exclusive or shared write lock");
        String depth = t.req.header("Depth");
        boolean deep = depth == null || depth.equalsIgnoreCase("infinity");
        if (!deep && !depth.equals("0")) throw HttpException.badRequest("a lock has Depth 0 or infinity");
        Element owner = info.child(Xml.DAV, "owner");
        DavNode node;
        Lock lock;
        try (Space space = t.space.enter()) {
            node = node(t, t.path);
            if (node == null) requireParent(t, t.path);
            guard(t, node == null ? required(t, t.path, true, false) : List.of());
            lock = space.locks().acquire(t.path, exclusive, deep, owner == null ? "" : owner.xml(), seconds);
            if (lock == null) throw new Refusal(423, "<D:no-conflicting-lock/>");
            if (node == null) {
                space.begin();
                try {
                    write(t.req, t.path, InputStream.nullInputStream(), OptionalLong.of(0)); // a lock on an unmapped URL creates an empty resource
                } catch (IOException | RuntimeException failure) {
                    space.locks().release(lock.token());
                    throw failure;
                } finally {
                    space.end();
                }
            }
        }
        t.res.status(node == null ? 201 : 200).header("Lock-Token", "<" + lock.token() + ">").type(XML);
        return HEAD + "<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery>" + activelock(t, lock) + "</D:lockdiscovery></D:prop>";
    }

    /** A {@code LOCK} without a body: renews the lock of the resource its {@code If} header names. */
    private Object refresh(Target t, long seconds) throws IOException {
        Lock refreshed;
        try (Space space = t.space.enter()) {
            Lock held = null;
            if (t.conditions != null) for (String token : t.conditions.tokens()) {
                Lock lock = space.locks().get(token);
                if (lock != null && lock.covers(t.path)) held = lock;
            }
            if (held == null) throw new HttpException(412, "a refresh names a lock of this resource in the If header");
            guard(t, List.of());
            refreshed = space.locks().refresh(held, seconds);
        }
        t.res.status(200).type(XML);
        return HEAD + "<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery>" + activelock(t, refreshed) + "</D:lockdiscovery></D:prop>";
    }

    private Object unlock(Target t) throws IOException {
        String header = t.req.header("Lock-Token");
        if (header == null) throw HttpException.badRequest("Lock-Token is required");
        String token = header.strip();
        if (token.startsWith("<") && token.endsWith(">")) token = token.substring(1, token.length() - 1); // Windows sends it bare
        try (Space space = t.space.enter()) {
            Lock lock = space.locks().get(token);
            if (lock == null || !lock.covers(t.path)) throw new Refusal(409, "<D:lock-token-matches-request-uri/>");
            space.locks().release(token);
        }
        t.res.status(204);
        return null;
    }

    // -- The quota ---------------------------------------------------------------

    /** RFC 4331: what is left of the quota, or of the free space when that is smaller; {@code null} when neither is known. */
    private static Quota quota(Target t, Space space, long limit) throws IOException {
        OptionalLong free = t.storage.free();
        if (limit == Long.MAX_VALUE && free.isEmpty()) return null;
        long used = space.used();
        long available = limit == Long.MAX_VALUE ? free.getAsLong() : Math.max(0, limit - used);
        if (free.isPresent()) available = Math.min(available, free.getAsLong());
        return new Quota(available, used);
    }

    /** The bytes the storage holds at the request's path, a file the handler hides included. */
    private static long stored(Target t) throws IOException {
        DavNode node = t.storage.stat(t.path).orElse(null);
        return node == null || node.collection() ? 0 : node.length();
    }

    private static Refusal quotaExceeded() {
        return new Refusal(507, "<D:quota-not-exceeded/>");
    }

    /**
     * The precondition a {@code 507} names when the store itself is out of room: its disk, or a quota
     * of its own. {@code null} for any other failure.
     */
    // ponytail: the JDK reports ENOSPC and EDQUOT only in the message, as the OS words it
    private static String full(Throwable failure) {
        String message = String.valueOf(failure.getMessage());
        if (message.contains("No space left on device") || message.contains("not enough space on the disk"))
            return "<D:sufficient-disk-space/>";
        if (message.contains("Disk quota exceeded")) return "<D:quota-not-exceeded/>";
        return null;
    }

    // -- Locks and conditions ----------------------------------------------------

    /**
     * The locks a write to {@code path} must hold a token of: those covering it, those of its parent
     * when the write adds or removes a member, and those below it when it removes a subtree.
     */
    private static List<Lock> required(Target t, DavPath path, boolean membership, boolean subtree) {
        Locks locks = t.space.locks();
        List<Lock> required = new ArrayList<>(locks.covering(path));
        if (membership && !path.isRoot()) required.addAll(locks.covering(path.parent()));
        if (subtree) required.addAll(locks.within(path));
        return required;
    }

    /** Refuses a read whose {@code If} header does not hold. */
    private void check(Target t) throws IOException {
        if (t.conditions == null) return;
        try (Space held = t.space.enter()) {
            guard(t, List.of());
        }
    }

    /**
     * Refuses a request whose {@code If} header does not hold ({@code 412}), then a write that locks
     * apply to when the {@code If} header names none of their tokens ({@code 423}). Called while the
     * space is held.
     */
    // ponytail: one submitted token of any lock that applies admits the write; per-lock tokens if shared locks under shared locks matter
    private void guard(Target t, List<Lock> required) throws IOException {
        if (t.conditions != null && !holds(t)) throw new HttpException(412, "the If header does not hold");
        if (required.isEmpty()) return;
        List<String> submitted = t.conditions == null ? List.of() : t.conditions.tokens();
        if (required.stream().noneMatch(lock -> submitted.contains(lock.token())))
            throw new Refusal(423, "<D:lock-token-submitted><D:href>" + Xml.escape(required.getFirst().root()
                    .href(t.mount, false)) + "</D:href></D:lock-token-submitted>");
    }

    /** Whether any list of the {@code If} header holds for the resource it is about. */
    private boolean holds(Target t) throws IOException {
        for (IfHeader.Clause clause : t.conditions.clauses()) {
            DavPath path = clause.resource() == null ? t.path : resolve(t, clause.resource());
            if (path == null) continue;
            boolean holds = true;
            for (IfHeader.Condition condition : clause.conditions()) {
                boolean match;
                if (condition.token() != null) {
                    match = false;
                    for (Lock lock : t.space.locks().covering(path)) match |= lock.token().equals(condition.token());
                } else {
                    DavNode node = node(t, path);
                    match = node != null && node.etag() != null && opaque(node.etag()).equals(opaque(condition.etag()));
                }
                if (match == condition.not()) {
                    holds = false;
                    break;
                }
            }
            if (holds) return true;
        }
        return false;
    }

    private static String activelock(Target t, Lock lock) {
        return "<D:activelock><D:locktype><D:write/></D:locktype><D:lockscope><D:" + (lock.exclusive() ? "exclusive" : "shared")
                + "/></D:lockscope><D:depth>" + (lock.deep() ? "infinity" : "0") + "</D:depth>"
                + (lock.owner().isEmpty() ? "" : "<D:owner>" + lock.owner() + "</D:owner>")
                + "<D:timeout>Second-" + lock.seconds() + "</D:timeout><D:locktoken><D:href>" + Xml.escape(lock.token())
                + "</D:href></D:locktoken><D:lockroot><D:href>" + Xml.escape(lock.root().href(t.mount, false))
                + "</D:href></D:lockroot></D:activelock>";
    }

    /** The first timeout the client asks for that this server grants, capped at the module's maximum. */
    private long timeout(String header) {
        if (header != null) for (String each : header.split(",")) {
            String option = each.strip();
            if (option.equalsIgnoreCase("Infinite")) return module.maxLockSeconds();
            if (option.regionMatches(true, 0, "Second-", 0, 7)) try {
                return Math.max(1, Math.min(module.maxLockSeconds(), Long.parseLong(option.substring(7))));
            } catch (NumberFormatException ignored) {
                // the next option
            }
        }
        return module.maxLockSeconds();
    }

    // -- Helpers -----------------------------------------------------------------

    private static DavStorage storage() {
        DavStorage storage = STORAGE.get();
        if (storage == null) throw new IllegalStateException("a DavHandler reaches its storage only while it handles a request");
        return storage;
    }

    private static DavStorage.Writable writer() {
        return (DavStorage.Writable) storage(); // only write methods call it, and they are refused for any other storage
    }

    private Map<DavPath, Map<QName, String>> memory(DavStorage storage) {
        return module.memory.computeIfAbsent(storage, key -> new ConcurrentHashMap<>());
    }

    private static String refuse(Response res, Refusal refused) {
        res.status(refused.status).type(XML);
        return HEAD + "<D:error xmlns:D=\"DAV:\">" + refused.condition + "</D:error>";
    }

    /** The path in this mount a URL or an absolute path names, or {@code null} when it is on another host or mount. */
    private static DavPath resolve(Target t, String url) {
        String raw = url;
        if (!url.startsWith("/")) {
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException malformed) {
                return null;
            }
            String host = t.req.header("Host");
            if (uri.getRawAuthority() == null || host == null || !uri.getRawAuthority().equalsIgnoreCase(host)) return null;
            raw = uri.getRawPath();
        }
        int end = raw.indexOf('?');
        if (end >= 0) raw = raw.substring(0, end);
        String[] mount = t.mount.split("/");
        String[] given = raw.split("/", -1);
        if (given.length < mount.length) return null;
        for (int i = 0; i < mount.length; i++) if (!DavPath.decode(given[i]).equals(DavPath.decode(mount[i]))) return null;
        return DavPath.parse(String.join("/", java.util.Arrays.copyOfRange(given, mount.length, given.length)));
    }

    private DavNode node(Target t, DavPath path) throws IOException {
        return stat(t.req, path).orElse(null);
    }

    private DavNode existing(Target t, DavPath path) throws IOException {
        DavNode node = node(t, path);
        if (node == null) throw HttpException.notFound(path.toString());
        return node;
    }

    private void requireParent(Target t, DavPath path) throws IOException {
        DavNode parent = path.isRoot() ? null : node(t, path.parent());
        if (parent == null || !parent.collection()) throw HttpException.conflict("the parent collection does not exist");
    }

    /** Deletes a resource and what is below it, members first; what fails stays, with its parents. */
    private boolean remove(Target t, Space space, DavPath path, DavNode node, List<Failure> failures) throws IOException {
        boolean emptied = true;
        if (node.collection())
            for (DavNode child : list(t.req, path)) emptied &= remove(t, space, path.child(child.name()), child, failures);
        if (!emptied) return false;
        try {
            delete(t.req, path);
        } catch (IOException | RuntimeException failure) {
            failures.add(new Failure(path, node.collection(), status(failure)));
            return false;
        }
        if (!(t.storage instanceof DavStorage.Properties)) memory(t.storage).remove(path);
        space.locks().releaseWithin(path);
        if (!node.collection()) space.settle(-node.length());
        return true;
    }

    /** Copies a resource, its dead properties, and with {@code deep} its members, one by one; each file is counted as it lands. */
    private void duplicate(Target t, DavPath from, DavNode node, DavPath to, boolean deep, List<Failure> failures) throws IOException {
        try {
            copy(t.req, from, to);
            Map<QName, String> properties = properties(t.req, from);
            if (!properties.isEmpty()) properties(t.req, to, properties);
        } catch (IOException | RuntimeException failure) {
            failures.add(new Failure(to, node.collection(), status(failure)));
            return;
        }
        if (!node.collection()) try (Space space = t.space.enter()) {
            space.settle(node.length());
        }
        else if (deep)
            for (DavNode child : list(t.req, from)) duplicate(t, from.child(child.name()), child, to.child(child.name()), true, failures);
    }

    /** {@code 207} listing what failed, or the plain status when only the resource the request names failed (RFC 4918 §9.6.1, §9.8.5). */
    private static Object failures(Target t, List<Failure> failures, DavPath named) {
        if (failures.size() == 1 && failures.getFirst().path().equals(named)) throw new HttpException(failures.getFirst().status(), "");
        StringBuilder answer = new StringBuilder(HEAD).append("<D:multistatus xmlns:D=\"DAV:\">");
        for (Failure failure : failures)
            answer.append("<D:response><D:href>").append(Xml.escape(failure.path().href(t.mount, failure.collection())))
                    .append("</D:href>").append(status(failure.status())).append("</D:response>");
        t.res.status(207).type(XML);
        return answer.append("</D:multistatus>").toString();
    }

    private static String status(int code) {
        String reason = HttpStatus.reasonForCode(code);
        return "<D:status>HTTP/1.1 " + code + " " + (reason == null ? "" : reason) + "</D:status>";
    }

    private static int status(Throwable failure) {
        if (failure instanceof HttpException refused) return refused.status();
        if (failure instanceof Refusal refused) return refused.status;
        if (failure instanceof NoSuchFileException) return 409;
        if (failure instanceof FileAlreadyExistsException || failure instanceof DirectoryNotEmptyException) return 409;
        if (failure instanceof AccessDeniedException) return 403;
        if (failure instanceof UnsupportedOperationException) return 405;
        return full(failure) != null ? 507 : 500;
    }

    /** RFC 9110 §13.2.2 in its order: {@code 412}, {@code 304}, or {@code 0} to go on. {@code node} is {@code null} when absent. */
    private static int preconditions(Request req, DavNode node, boolean read) {
        String ifMatch = req.header("If-Match");
        Instant unmodified = date(req.header("If-Unmodified-Since"));
        if (ifMatch != null ? !tagMatches(ifMatch, node, true)
                : unmodified != null && node != null && node.modified().truncatedTo(ChronoUnit.SECONDS).isAfter(unmodified)) return 412;
        String ifNoneMatch = req.header("If-None-Match");
        Instant modified = date(req.header("If-Modified-Since"));
        if (ifNoneMatch != null) return tagMatches(ifNoneMatch, node, false) ? read ? 304 : 412 : 0;
        if (read && modified != null && node != null && !node.modified().truncatedTo(ChronoUnit.SECONDS).isAfter(modified)) return 304;
        return 0;
    }

    private static boolean tagMatches(String header, DavNode node, boolean strong) {
        if (node == null) return false;
        if (header.strip().equals("*")) return true;
        for (String tag : header.split(",")) {
            String candidate = tag.strip();
            if (strong ? !candidate.startsWith("W/") && candidate.equals(node.etag()) : opaque(candidate).equals(opaque(node.etag()))) return true;
        }
        return false;
    }

    private static String opaque(String etag) {
        return etag.startsWith("W/") ? etag.substring(2) : etag;
    }

    private static boolean rangeApplies(String ifRange, DavNode node) {
        if (ifRange == null) return true;
        if (ifRange.startsWith("\"")) return ifRange.equals(node.etag());
        Instant date = date(ifRange);
        return date != null && date.equals(node.modified().truncatedTo(ChronoUnit.SECONDS));
    }

    /** The first and last byte of one range; {@code null} to send the whole file; empty when unsatisfiable. */
    private static long[] range(String header, long length) {
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0) return null; // several ranges: the whole file, as RFC 9110 allows
        String spec = header.substring(6).strip();
        int dash = spec.indexOf('-');
        try {
            if (dash < 0) return null;
            if (dash == 0) {
                long suffix = Long.parseLong(spec.substring(1));
                return suffix <= 0 || length == 0 ? new long[0] : new long[] {Math.max(0, length - suffix), length - 1};
            }
            long first = Long.parseLong(spec.substring(0, dash));
            if (first >= length) return new long[0];
            long last = dash == spec.length() - 1 ? length - 1 : Long.parseLong(spec.substring(dash + 1));
            return last < first ? null : new long[] {first, Math.min(last, length - 1)};
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static Instant date(String header) {
        if (header == null) return null;
        try {
            return ZonedDateTime.parse(header.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static List<QName> names(Element prop) {
        return prop.children().stream().map(Element::qname).distinct().toList();
    }
}
