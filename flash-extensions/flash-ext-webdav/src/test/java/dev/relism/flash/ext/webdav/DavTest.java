package dev.relism.flash.ext.webdav;

import dev.relism.flash.ext.webdav.nio.StandaloneNioDavHandler;
import dev.relism.flash.ext.webdav.nio.ClusteredNioDavHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import dev.relism.flash.App;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.testing.FlashResponse;
import dev.relism.flash.testing.FlashTest;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Request;
import java.nio.file.AccessDeniedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.xml.namespace.QName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Every method and status of the protocol table, over an in-memory file system with extended attributes. */
class DavTest {

    static final String XML_HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";

    /** A directory, the same for every request. */
    public static class Dir extends StandaloneNioDavHandler {
        private final Path root;
        public Dir(Path root) { this.root = root; }
        @Override protected Path root(Request req) { return root; }
    }

    /** Refuses to delete one name, and remembers what was downloaded: control through the operations' methods. */
    public static final class Stubborn extends Dir {
        static final List<String> downloaded = new CopyOnWriteArrayList<>();
        public Stubborn(Path root) { super(root); }

        @Override protected void delete(Request req, DavPath path) throws IOException {
            if (path.name().equals("keep.txt")) throw HttpException.forbidden("kept");
            super.delete(req, path);
        }

        @Override protected InputStream read(Request req, DavPath file, long offset, long length) throws IOException {
            downloaded.add(file.toString());
            return super.read(req, file, offset, length);
        }
    }

    /** One directory per user, named by a header, with a quota; read-only for a guest. */
    public static final class Drive extends StandaloneNioDavHandler {
        private final Path root;
        public Drive(Path root) { this.root = root; }
        @Override protected Path root(Request req) { return root.resolve(req.header("X-User")); }
        @Override protected long quota(Request req) { return 10; }
        @Override protected boolean writable(Request req) { return !req.header("X-User").equals("guest"); }
    }

    /** A directory other processes serve too: its state in .dav under the root. */
    public static final class Clustered extends ClusteredNioDavHandler {
        private final Path root;
        public Clustered(Path root) { this.root = root; }
        @Override protected Path root(Request req) { return root; }
    }

    /** A clustered directory whose state clients see. */
    public static final class Open extends ClusteredNioDavHandler {
        private final Path root;
        public Open(Path root) { this.root = root; }
        @Override protected Path root(Request req) { return root; }
        @Override protected boolean stateVisible() { return true; }
    }

    /** A storage that only reads: the type says so. */
    record ArchiveStorage() implements DavStorage.Properties {
        @Override public Optional<DavNode> stat(DavPath path) {
            if (path.isRoot()) return Optional.of(DavNode.collection("", Instant.EPOCH));
            return path.toString().equals("/a.txt") ? Optional.of(DavNode.file("a.txt", 3, Instant.EPOCH)) : Optional.empty();
        }
        @Override public List<DavNode> list(DavPath path) { return List.of(stat(DavPath.parse("a.txt")).orElseThrow()); }
        @Override public InputStream read(DavPath path, long offset, long length) {
            return new ByteArrayInputStream("abc".getBytes(), (int) offset, (int) length);
        }
        /** Like a directory the server's user may list but not enter. */
        @Override public Map<QName, String> properties(DavPath path) throws IOException {
            if (path.name().equals("a.txt")) throw new AccessDeniedException(path.toString());
            return Map.of();
        }
        @Override public void properties(DavPath path, Map<QName, String> properties) {}
    }

    public static final class Archive extends DavHandler {
        @Override protected DavStorage open(Request req) { return new ArchiveStorage(); }
    }

    private FileSystem fs;
    private Path root;

    @RegisterExtension
    final FlashTest app = FlashTest.of(() -> App.create()
            .install(new DavModule().maxLockTimeout(Duration.ofMinutes(20)))
            .any("/files/**", new Stubborn(root))
            .any("/archive/**", new Archive())
            .any("/drive/**", new Drive(root.resolve("drive")))
            .any("/cluster/**", new Clustered(root.resolve("cluster")))
            .any("/theirs/**", new Clustered(root.resolve("theirs")))
            .any("/plain/**", new Dir(root.resolve("plain")))
            .any("/open/**", new Open(root.resolve("open")))
            .mount("/api", api -> api.any("/dav/**", new Dir(root))));

    @BeforeEach
    void storage() throws IOException {
        fs = Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("basic", "user").build());
        root = Files.createDirectories(fs.getPath("/srv/files"));
    }

    private FlashResponse dav(String method, String path, String body, String... headers) {
        var request = app.request();
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        if (body != null) request.body(body);
        return request.send(method, path);
    }

    private String put(String path, String content) {
        return dav("PUT", path, content).expectStatus(201).header("ETag");
    }

    private static String locked(FlashResponse response) {
        return response.header("Lock-Token").replaceAll("[<>]", "");
    }

    private static final String EXCLUSIVE = "<D:lockinfo xmlns:D=\"DAV:\"><D:lockscope><D:exclusive/></D:lockscope>"
            + "<D:locktype><D:write/></D:locktype><D:owner><D:href>mailto:a@b</D:href></D:owner></D:lockinfo>";

    @Test
    void optionsNamesTheClassesAndTheMethodsTheStorageServes() {
        dav("OPTIONS", "/files/", null).expectStatus(200).expectHeader("DAV", "1, 2").expectHeader("MS-Author-Via", "DAV")
                .expectHeader("Allow", "OPTIONS, GET, HEAD, PROPFIND, PUT, PROPPATCH, COPY, LOCK, UNLOCK, MKCOL, DELETE, MOVE");
        dav("OPTIONS", "/archive/a.txt", null).expectHeader("DAV", "1").expectHeader("Allow", "OPTIONS, GET, HEAD, PROPFIND");
        dav("PUT", "/archive/b.txt", "x").expectStatus(405).expectHeader("Allow", "OPTIONS, GET, HEAD, PROPFIND");
        dav("POST", "/files/x", "x").expectStatus(405);
        dav("REPORT", "/files/", null).expectStatus(501);
    }

    @Test
    void putAndGetWithEntityTagsDatesAndConditions() {
        String etag = put("/files/a.txt", "hello");
        dav("PUT", "/files/a.txt", "hello!").expectStatus(204);
        FlashResponse got = dav("GET", "/files/a.txt", null).expectStatus(200).expectBody("hello!")
                .expectHeader("Content-Type", "text/plain; charset=utf-8").expectHeader("Accept-Ranges", "bytes");
        String current = got.header("ETag");
        assertTrue(!current.equals(etag), "the tag changes with the content");
        assertTrue(got.header("Last-Modified").matches("[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT"),
                got.header("Last-Modified"));

        dav("GET", "/files/a.txt", null, "If-None-Match", current).expectStatus(304);
        dav("GET", "/files/a.txt", null, "If-Modified-Since", got.header("Last-Modified")).expectStatus(304);
        dav("GET", "/files/a.txt", null, "If-Match", etag).expectStatus(412);
        dav("PUT", "/files/a.txt", "x", "If-Match", etag).expectStatus(412);
        dav("PUT", "/files/a.txt", "x", "If-None-Match", "*").expectStatus(412);
        dav("PUT", "/files/new.txt", "x", "If-Match", "*").expectStatus(412);
        assertEquals("6", dav("HEAD", "/files/a.txt", null).expectStatus(200).expectBody("").header("Content-Length"));

        dav("PUT", "/files/none/a.txt", "x").expectStatus(409);
        dav("MKCOL", "/files/dir", null).expectStatus(201);
        dav("PUT", "/files/dir", "x").expectStatus(405);
        dav("PUT", "/files/a.txt/b", "x").expectStatus(409);
        dav("PUT", "/files/a.txt", "x", "Content-Range", "bytes 0-0/1").expectStatus(400);
        dav("GET", "/files/missing", null).expectStatus(404);
    }

    @Test
    void oneRangeIsPartialSeveralAreTheWholeFileAndAnImpossibleOneIs416() {
        put("/files/r.bin", "0123456789");
        dav("GET", "/files/r.bin", null, "Range", "bytes=2-4").expectStatus(206).expectBody("234")
                .expectHeader("Content-Range", "bytes 2-4/10");
        dav("GET", "/files/r.bin", null, "Range", "bytes=-3").expectStatus(206).expectBody("789");
        dav("GET", "/files/r.bin", null, "Range", "bytes=7-").expectStatus(206).expectBody("789");
        dav("GET", "/files/r.bin", null, "Range", "bytes=8-100").expectStatus(206).expectBody("89");
        dav("GET", "/files/r.bin", null, "Range", "bytes=0-1,4-5").expectStatus(200).expectBody("0123456789");
        dav("GET", "/files/r.bin", null, "Range", "bytes=10-").expectStatus(416).expectHeader("Content-Range", "bytes */10");
        dav("GET", "/files/r.bin", null, "Range", "bytes=2-4", "If-Range", "\"stale\"").expectStatus(200);
    }

    @Test
    void aChunkedPutWithTheLengthFinderAnnouncesIsStored() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(app.baseUri() + "/files/finder.bin"))
                .header("X-Expected-Entity-Length", "5")
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream("chunk".getBytes())))
                .build();
        assertEquals(201, app.client().send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        dav("GET", "/files/finder.bin", null).expectBody("chunk");
    }

    @Test
    void mkcolAndDelete() {
        dav("MKCOL", "/files/c", null).expectStatus(201);
        dav("MKCOL", "/files/c", null).expectStatus(405);
        dav("MKCOL", "/files/x/y", null).expectStatus(409);
        dav("MKCOL", "/files/d", "<x/>").expectStatus(415);
        put("/files/c/a.txt", "a");
        dav("MKCOL", "/files/c/sub", null).expectStatus(201);
        put("/files/c/sub/b.txt", "b");
        dav("DELETE", "/files/c", null).expectStatus(204);
        dav("PROPFIND", "/files/c", null, "Depth", "0").expectStatus(404);
        dav("DELETE", "/files/c", null).expectStatus(404);
        dav("DELETE", "/files/", null).expectStatus(403);
    }

    @Test
    void aDeleteThatFailsInPartAnswers207WithWhatStayed() {
        dav("MKCOL", "/files/p", null).expectStatus(201);
        put("/files/p/keep.txt", "k");
        put("/files/p/go.txt", "g");
        FlashResponse partial = dav("DELETE", "/files/p", null).expectStatus(207);
        assertTrue(partial.body().contains("<D:href>/files/p/keep.txt</D:href><D:status>HTTP/1.1 403 Forbidden</D:status>"),
                partial.body());
        dav("GET", "/files/p/go.txt", null).expectStatus(404);
        dav("GET", "/files/p/keep.txt", null).expectStatus(200);
    }

    @Test
    void copyAndMoveFollowTheirTable() {
        put("/files/s.txt", "source");
        String here = app.baseUri().toString();
        dav("COPY", "/files/s.txt", null, "Destination", here + "/files/t.txt").expectStatus(201);
        dav("COPY", "/files/s.txt", null, "Destination", "/files/t.txt").expectStatus(204);
        dav("COPY", "/files/s.txt", null, "Destination", "/files/t.txt", "Overwrite", "F").expectStatus(412);
        dav("COPY", "/files/s.txt", null, "Destination", "http://elsewhere.example/files/t.txt").expectStatus(502);
        dav("COPY", "/files/s.txt", null, "Destination", "/archive/t.txt").expectStatus(502);
        dav("COPY", "/files/s.txt", null, "Destination", "/files/s.txt").expectStatus(403);
        dav("COPY", "/files/s.txt", null, "Destination", "/files/none/t.txt").expectStatus(409);
        dav("COPY", "/files/s.txt", null).expectStatus(400);
        dav("MOVE", "/files/t.txt", null, "Destination", "/files/u%20v.txt").expectStatus(201);
        dav("GET", "/files/u%20v.txt", null).expectBody("source");
        dav("GET", "/files/t.txt", null).expectStatus(404);

        dav("MKCOL", "/files/tree", null).expectStatus(201);
        put("/files/tree/leaf.txt", "leaf");
        dav("COPY", "/files/tree", null, "Destination", "/files/shallow", "Depth", "0").expectStatus(201);
        dav("GET", "/files/shallow/leaf.txt", null).expectStatus(404);
        dav("COPY", "/files/tree", null, "Destination", "/files/deep").expectStatus(201);
        dav("GET", "/files/deep/leaf.txt", null).expectBody("leaf");
        dav("COPY", "/files/tree", null, "Destination", "/files/tree/inner").expectStatus(403);
        dav("MOVE", "/files/tree", null, "Destination", "/files/moved", "Depth", "0").expectStatus(400);
        dav("MOVE", "/files/tree", null, "Destination", "/files/deep").expectStatus(204);
        dav("GET", "/files/deep/leaf.txt", null).expectBody("leaf");
        dav("COPY", "/api/dav/s.txt", null, "Destination", "/api/dav/w.txt").expectStatus(201); // under a mount prefix
    }

    @Test
    void propfindAnswersFiniteDepthsWithLiveAndDeadProperties() {
        put("/files/a.txt", "abc");
        dav("MKCOL", "/files/d", null).expectStatus(201);
        dav("PROPFIND", "/files/", null).expectStatus(403)
                .expectBody(XML_HEAD + "\n<D:error xmlns:D=\"DAV:\"><D:propfind-finite-depth/></D:error>");
        dav("PROPFIND", "/files/", null, "Depth", "infinity").expectStatus(403);
        dav("PROPFIND", "/files/", null, "Depth", "2").expectStatus(400);

        String all = dav("PROPFIND", "/files/", null, "Depth", "1").expectStatus(207)
                .expectHeader("Content-Type", "application/xml; charset=utf-8").body();
        assertTrue(all.contains("<D:href>/files/</D:href>") && all.contains("<D:href>/files/d/</D:href>")
                && all.contains("<D:href>/files/a.txt</D:href>"), all);
        assertTrue(all.contains("<D:getcontentlength>3</D:getcontentlength>") && all.contains("<D:resourcetype><D:collection/></D:resourcetype>")
                && all.contains("<D:supportedlock><D:lockentry>") && all.contains("<D:creationdate>"), all);
        assertTrue(!all.contains("displayname") && !all.contains("quota"), "not emitted unless asked: " + all);

        String some = dav("PROPFIND", "/files/a.txt", "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:getetag/><D:quota-available-bytes/>"
                + "<z:missing xmlns:z=\"urn:z\"/></D:prop></D:propfind>", "Depth", "0").expectStatus(207).body();
        assertTrue(some.contains("<D:getetag>&quot;") && some.contains("<D:quota-available-bytes>"), some);
        assertTrue(some.contains("<x:missing xmlns:x=\"urn:z\"/></D:prop><D:status>HTTP/1.1 404 Not Found</D:status>"), some);

        String names = dav("PROPFIND", "/files/a.txt", "<propfind xmlns=\"DAV:\"><propname/></propfind>", "Depth", "0").body();
        assertTrue(names.contains("<D:getcontentlength/>") && !names.contains("abc"), names);
        dav("PROPFIND", "/files/a.txt", "<D:propfind xmlns:D=\"DAV:\"/>", "Depth", "0").expectStatus(400);
        dav("PROPFIND", "/files/a.txt", "not xml", "Depth", "0").expectStatus(400);
    }

    /** A member whose dead properties cannot be read is still listed; the listing does not fail. */
    @Test
    void aMemberWhosePropertiesCannotBeReadIsStillListed() {
        String listing = dav("PROPFIND", "/archive/", null, "Depth", "1").expectStatus(207).body();
        assertTrue(listing.contains("<D:href>/archive/a.txt</D:href>") && listing.contains("<D:getcontentlength>3</D:getcontentlength>"),
                listing);
        String named = dav("PROPFIND", "/archive/a.txt", "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:getetag/><c xmlns=\"urn:c\"/>"
                + "</D:prop></D:propfind>", "Depth", "0").expectStatus(207).body();
        assertTrue(named.contains("<x:c xmlns:x=\"urn:c\"/></D:prop><D:status>HTTP/1.1 403 Forbidden</D:status>") && named.contains("<D:getetag>"), named);
    }

    @Test
    void proppatchKeepsDeadPropertiesExactlyAndProtectsLiveOnes() {
        put("/files/p.txt", "p");
        String set = "<D:propertyupdate xmlns:D=\"DAV:\" xmlns:Z=\"urn:schemas-microsoft-com:\" xmlns:v=\"urn:v\"><D:set><D:prop>"
                + "<Z:Win32LastModifiedTime>Tue, 14 Oct 2026 10:00:00 GMT</Z:Win32LastModifiedTime>"
                + "<v:nested><v:inner a=\"1 &amp; 2\">x &lt; y</v:inner></v:nested></D:prop></D:set>"
                + "<D:remove><D:prop><v:gone/></D:prop></D:remove></D:propertyupdate>";
        String patched = dav("PROPPATCH", "/files/p.txt", set).expectStatus(207).body();
        assertTrue(patched.contains("HTTP/1.1 200 OK") && !patched.contains("424"), patched);
        String read = dav("PROPFIND", "/files/p.txt", "<D:propfind xmlns:D=\"DAV:\"><D:allprop/></D:propfind>", "Depth", "0").body();
        assertTrue(read.contains("<x:Win32LastModifiedTime xmlns:x=\"urn:schemas-microsoft-com:\">Tue, 14 Oct 2026 10:00:00 GMT"), read);
        assertTrue(read.contains("<x:nested xmlns:x=\"urn:v\"><n:inner xmlns:n=\"urn:v\" a=\"1 &amp; 2\">x &lt; y</n:inner></x:nested>"), read);

        String refused = dav("PROPPATCH", "/files/p.txt", "<D:propertyupdate xmlns:D=\"DAV:\"><D:set><D:prop><D:getetag>x</D:getetag>"
                + "<o:other xmlns:o=\"urn:o\">1</o:other></D:prop></D:set></D:propertyupdate>").expectStatus(207).body();
        assertTrue(refused.contains("<D:getetag/></D:prop><D:status>HTTP/1.1 403 Forbidden")
                && refused.contains("<x:other xmlns:x=\"urn:o\"/></D:prop><D:status>HTTP/1.1 424 Failed Dependency"), refused);
        assertTrue(!dav("PROPFIND", "/files/p.txt", null, "Depth", "0").body().contains("urn:o"), "nothing applied");

        dav("COPY", "/files/p.txt", null, "Destination", "/files/q.txt").expectStatus(201);
        dav("MOVE", "/files/q.txt", null, "Destination", "/files/r.txt").expectStatus(201);
        assertTrue(dav("PROPFIND", "/files/r.txt", null, "Depth", "0").body().contains("Win32LastModifiedTime"), "copied and moved");
    }

    @Test
    void locksExcludeOthersUntilTheirTokenIsShownOrTheyAreReleased() {
        put("/files/l.txt", "l");
        FlashResponse lock = dav("LOCK", "/files/l.txt", EXCLUSIVE, "Timeout", "Second-600").expectStatus(200);
        String token = locked(lock);
        assertTrue(token.startsWith("urn:uuid:") && lock.body().contains("<D:timeout>Second-600</D:timeout>")
                && lock.body().contains("<D:owner><n:href xmlns:n=\"DAV:\">mailto:a@b</n:href></D:owner>"), lock.body());

        dav("PUT", "/files/l.txt", "x").expectStatus(423).expectBodyContains("<D:lock-token-submitted><D:href>/files/l.txt</D:href>");
        dav("DELETE", "/files/l.txt", null).expectStatus(423);
        dav("LOCK", "/files/l.txt", EXCLUSIVE).expectStatus(423).expectBodyContains("no-conflicting-lock");
        dav("PUT", "/files/l.txt", "x", "If", "(<" + token + ">)").expectStatus(204);
        dav("PUT", "/files/l.txt", "x", "If", "(<" + token + "> [\"wrong\"])").expectStatus(412);
        dav("PUT", "/files/l.txt", "x", "If", "(<urn:uuid:other>) (Not <DAV:no-lock>)").expectStatus(423);
        dav("PUT", "/files/l.txt", "x", "If", "<" + app.baseUri() + "/files/l.txt> (<" + token + ">)").expectStatus(204);
        assertTrue(dav("PROPFIND", "/files/l.txt", null, "Depth", "0").body().contains("<D:locktoken><D:href>" + token), "discovery");

        dav("LOCK", "/files/l.txt", null, "Timeout", "Infinite", "If", "(<" + token + ">)").expectStatus(200)
                .expectBodyContains("<D:timeout>Second-1200</D:timeout>");
        dav("LOCK", "/files/l.txt", null).expectStatus(412);
        dav("UNLOCK", "/files/l.txt", null, "Lock-Token", "<urn:uuid:nope>").expectStatus(409)
                .expectBodyContains("lock-token-matches-request-uri");
        dav("UNLOCK", "/files/l.txt", null, "Lock-Token", token).expectStatus(204); // bare, as Windows sends it
        dav("PUT", "/files/l.txt", "free").expectStatus(204);
    }

    @Test
    void aLockOnAnUnmappedUrlCreatesAnEmptyResourceAndADeepLockCoversMembers() {
        FlashResponse created = dav("LOCK", "/files/new.txt", EXCLUSIVE).expectStatus(201);
        dav("GET", "/files/new.txt", null).expectStatus(200).expectBody("");
        dav("PUT", "/files/new.txt", "x", "If", "(<" + locked(created) + ">)").expectStatus(204);

        dav("MKCOL", "/files/locked", null).expectStatus(201);
        String token = locked(dav("LOCK", "/files/locked", EXCLUSIVE.replace("exclusive", "shared")).expectStatus(200));
        dav("PUT", "/files/locked/member.txt", "x").expectStatus(423);
        dav("PUT", "/files/locked/member.txt", "x", "If", "(<" + token + ">)").expectStatus(201);
        dav("LOCK", "/files/locked/member.txt", EXCLUSIVE).expectStatus(423);
        dav("LOCK", "/files/locked", EXCLUSIVE.replace("exclusive", "shared")).expectStatus(200);
        dav("MOVE", "/files/locked", null, "Destination", "/files/elsewhere").expectStatus(423);
        dav("LOCK", "/files/x", EXCLUSIVE, "Depth", "1").expectStatus(400);
    }

    @Test
    void hostileXmlIsRefused() {
        put("/files/x.txt", "x");
        String external = "<?xml version=\"1.0\"?><!DOCTYPE p [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:getetag>&e;</D:getetag></D:prop></D:propfind>";
        dav("PROPFIND", "/files/x.txt", external, "Depth", "0").expectStatus(400);
        String laughs = "<?xml version=\"1.0\"?><!DOCTYPE l [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">]>"
                + "<D:propertyupdate xmlns:D=\"DAV:\"><D:set><D:prop><v xmlns=\"urn:v\">&b;</v></D:prop></D:set></D:propertyupdate>";
        dav("PROPPATCH", "/files/x.txt", laughs).expectStatus(400);
        dav("PROPPATCH", "/files/x.txt", "<a>" + "x".repeat(Xml.MAX_BODY) + "</a>").expectStatus(413);
    }

    @Test
    void aSymbolicLinkOutOfTheRootIsRefusedAndNotListed() throws IOException {
        Path outside = Files.writeString(fs.getPath("/srv/secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("leak.txt"), outside);
        Files.writeString(root.resolve("ok.txt"), "ok");
        Files.createSymbolicLink(root.resolve("alias.txt"), root.resolve("ok.txt"));
        dav("GET", "/files/leak.txt", null).expectStatus(403);
        dav("GET", "/files/alias.txt", null).expectStatus(200).expectBody("ok");
        String listing = dav("PROPFIND", "/files/", null, "Depth", "1").body();
        assertTrue(!listing.contains("leak.txt") && listing.contains("alias.txt"), listing);
    }

    @Test
    void linksInsideTheRootAreWalkedThroughAndALinkToNothingIsNothing() throws IOException {
        Files.createDirectories(root.resolve("real"));
        Files.writeString(root.resolve("real/in.txt"), "in");
        Files.createSymbolicLink(root.resolve("via"), root.resolve("real"));
        Files.createSymbolicLink(root.resolve("dangling.txt"), root.resolve("nowhere.txt"));
        Files.createSymbolicLink(root.resolve("out"), Files.createDirectories(fs.getPath("/srv/elsewhere")));
        dav("GET", "/files/via/in.txt", null).expectStatus(200).expectBody("in");
        assertTrue(dav("PROPFIND", "/files/via/", null, "Depth", "1").body().contains("in.txt"));
        dav("GET", "/files/dangling.txt", null).expectStatus(404);
        dav("GET", "/files/real/in.txt/below", null).expectStatus(404);
        dav("PUT", "/files/out/new.txt", "x").expectStatus(403);
        assertTrue(Files.notExists(fs.getPath("/srv/elsewhere/new.txt")));
    }

    @Test
    void aCollectionGetIsAListingOfItsMembers() {
        dav("MKCOL", "/files/g", null).expectStatus(201);
        put("/files/g/a%20b.txt", "x");
        dav("GET", "/files/g/", null).expectStatus(200).expectBodyContains("<a href=\"/files/g/a%20b.txt\">a b.txt</a>");
        dav("GET", "/archive/", null).expectBodyContains("a.txt");
    }

    /** Where the file system has no extended attributes, dead properties live in memory, and follow moves. */
    @Test
    void withoutExtendedAttributesDeadPropertiesAreKeptInMemory() throws Exception {
        FileSystem plain = Jimfs.newFileSystem(Configuration.unix());
        Path files = Files.createDirectories(plain.getPath("/plain"));
        FlashTest memory = FlashTest.of(() -> App.create().install(new DavModule()).any("/m/**", new Dir(files)));
        try {
            memory.request().body("x").put("/m/a.txt").expectStatus(201);
            memory.request().body("<D:propertyupdate xmlns:D=\"DAV:\"><D:set><D:prop><c xmlns=\"urn:c\">crimson</c></D:prop></D:set>"
                    + "</D:propertyupdate>").send("PROPPATCH", "/m/a.txt").expectStatus(207);
            memory.request().header("Destination", "/m/b.txt").send("MOVE", "/m/a.txt").expectStatus(201);
            assertTrue(memory.request().header("Depth", "0").send("PROPFIND", "/m/b.txt").body().contains(">crimson</x:c>"), "moved");
            memory.request().send("DELETE", "/m/b.txt").expectStatus(204);
            memory.request().body("y").put("/m/b.txt").expectStatus(201);
            assertTrue(!memory.request().header("Depth", "0").send("PROPFIND", "/m/b.txt").body().contains("crimson"), "forgotten");
        } finally {
            memory.afterAll(null);
        }
    }

    private static final String QUOTA = "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:quota-available-bytes/><D:quota-used-bytes/>"
            + "</D:prop></D:propfind>";

    @Test
    void aQuotaIsReportedAndEnforcedForEachSpace() throws Exception {
        dav("PUT", "/drive/a.txt", "12345", "X-User", "ann").expectStatus(201);
        String quota = dav("PROPFIND", "/drive/", QUOTA, "Depth", "0", "X-User", "ann").expectStatus(207).body();
        assertTrue(quota.contains("<D:quota-available-bytes>5</D:quota-available-bytes><D:quota-used-bytes>5</D:quota-used-bytes>"), quota);

        dav("PUT", "/drive/b.txt", "123456", "X-User", "ann").expectStatus(507).expectBodyContains("<D:quota-not-exceeded/>");
        dav("PUT", "/drive/a.txt", "123456", "X-User", "ann").expectStatus(507); // old and new are held together until the new lands
        dav("PUT", "/drive/a.txt", "123", "X-User", "ann").expectStatus(204);
        dav("PUT", "/drive/b.txt", "123456", "X-User", "bob").expectStatus(201); // another space
        dav("COPY", "/drive/a.txt", null, "Destination", "/drive/c.txt", "X-User", "ann").expectStatus(201);
        dav("COPY", "/drive/a.txt", null, "Destination", "/drive/d.txt", "X-User", "ann").expectStatus(201);
        dav("COPY", "/drive/a.txt", null, "Destination", "/drive/e.txt", "X-User", "ann").expectStatus(507);
        dav("DELETE", "/drive/c.txt", null, "X-User", "ann").expectStatus(204);
        dav("DELETE", "/drive/d.txt", null, "X-User", "ann").expectStatus(204);
        dav("PUT", "/drive/b.txt", "123456", "X-User", "ann").expectStatus(201);

        HttpRequest chunked = HttpRequest.newBuilder(URI.create(app.baseUri() + "/drive/big.bin")).header("X-User", "ann")
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[100]))).build();
        assertEquals(507, app.client().send(chunked, HttpResponse.BodyHandlers.discarding()).statusCode());
        dav("GET", "/drive/big.bin", null, "X-User", "ann").expectStatus(404);
        assertTrue(dav("PROPFIND", "/drive/", QUOTA, "Depth", "0", "X-User", "ann").body().contains("<D:quota-used-bytes>9<"), "counted");
    }

    /** A PUT is checked again once its body has arrived: a lock or a change made meanwhile wins. */
    @Test
    void aPutWhoseRequestNoLongerHoldsWhenItsBodyEndsReplacesNothing() throws Exception {
        String etag = put("/files/race.txt", "old");
        Socket slow = slowPut("/files/race.txt");
        String token = locked(dav("LOCK", "/files/race.txt", EXCLUSIVE).expectStatus(200));
        assertEquals(423, finish(slow));
        dav("UNLOCK", "/files/race.txt", null, "Lock-Token", token).expectStatus(204);
        dav("GET", "/files/race.txt", null).expectBody("old");

        Socket stale = slowPut("/files/race.txt", "If-Match", etag);
        dav("PUT", "/files/race.txt", "newer").expectStatus(204);
        assertEquals(412, finish(stale), "the change made meanwhile is not lost");
        dav("GET", "/files/race.txt", null).expectBody("newer");
    }

    /** A chunked PUT that sends one byte and waits: once it returns, the storage is writing it. */
    private Socket slowPut(String path, String... headers) throws Exception {
        Socket socket = new Socket("localhost", app.port());
        StringBuilder head = new StringBuilder("PUT " + path + " HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n");
        for (int i = 0; i < headers.length; i += 2) head.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
        socket.getOutputStream().write((head + "\r\n1\r\nx\r\n").getBytes(StandardCharsets.US_ASCII));
        Instant deadline = Instant.now().plusSeconds(10);
        while (!uploading()) {
            assertTrue(Instant.now().isBefore(deadline), "the storage starts writing");
            Thread.sleep(5);
        }
        return socket;
    }

    private boolean uploading() throws IOException {
        try (var files = Files.list(root)) {
            return files.anyMatch(each -> each.getFileName().toString().endsWith(".part"));
        }
    }

    /** Ends the body of a slow PUT; the status of its answer. */
    private static int finish(Socket socket) throws IOException {
        try (socket) {
            socket.getOutputStream().write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    @Test
    void aCallerThatMayNotWriteSeesAReadOnlyMount() {
        dav("OPTIONS", "/drive/", null, "X-User", "guest").expectHeader("DAV", "1").expectHeader("Allow", "OPTIONS, GET, HEAD, PROPFIND");
        dav("PUT", "/drive/x.txt", "x", "X-User", "guest").expectStatus(405);
        assertTrue(dav("PROPFIND", "/drive/", null, "Depth", "0", "X-User", "guest").body().contains("<D:supportedlock/>"));
    }

    @Test
    void theOperationsMethodsRefuseAndObserve() {
        put("/files/keep.txt", "k");
        dav("DELETE", "/files/keep.txt", null).expectStatus(403); // the named resource alone failed: its own status, no 207
        Stubborn.downloaded.clear();
        dav("GET", "/files/keep.txt", null).expectStatus(200);
        assertEquals(List.of("/keep.txt"), Stubborn.downloaded);
    }

    @Test
    void twoMountsOverTheSameDirectoryShareTheirLocks() {
        put("/files/shared.txt", "s");
        String token = locked(dav("LOCK", "/files/shared.txt", EXCLUSIVE).expectStatus(200));
        dav("PUT", "/api/dav/shared.txt", "x").expectStatus(423);
        dav("PUT", "/api/dav/shared.txt", "x", "If", "(<" + token + ">)").expectStatus(204);
    }

    @Test
    void aClusteredDirectoryKeepsItsStateInDavWhichNoRequestReaches() throws IOException {
        dav("PUT", "/cluster/a.txt", "a").expectStatus(201);
        Path cluster = root.resolve("cluster");
        assertTrue(Files.size(cluster.resolve(".dav/state")) > 0);
        String listing = dav("PROPFIND", "/cluster/", null, "Depth", "1").body();
        assertTrue(listing.contains("a.txt") && !listing.contains(".dav"), listing);
        assertTrue(!dav("GET", "/cluster/", null).body().contains(".dav"));

        dav("GET", "/cluster/.dav/state", null).expectStatus(403);
        dav("PROPFIND", "/cluster/.dav", null, "Depth", "0").expectStatus(403);
        dav("MKCOL", "/cluster/.dav", null).expectStatus(403);
        dav("PUT", "/cluster/.dav/x", "x").expectStatus(403);
        dav("DELETE", "/cluster/.dav", null).expectStatus(403);
        dav("MOVE", "/cluster/a.txt", null, "Destination", "/cluster/.dav").expectStatus(403);
        dav("COPY", "/cluster/a.txt", null, "Destination", "/cluster/.dav/a.txt").expectStatus(403);
        dav("GET", "/cluster/a.txt", null).expectBody("a");

        dav("MKCOL", "/cluster/sub", null).expectStatus(201);
        dav("MKCOL", "/cluster/sub/.dav", null).expectStatus(201); // only the one under the root is kept
        dav("PUT", "/cluster/sub/.dav/b.txt", "b").expectStatus(201);
        dav("MKCOL", "/cluster/.DAV", null).expectStatus(201); // another name on a case-sensitive store

        Files.createSymbolicLink(cluster.resolve("sub/state"), cluster.resolve(".dav"));
        Files.createSymbolicLink(cluster.resolve("uploads"), cluster.resolve(".dav/uploads"));
        dav("GET", "/cluster/sub/state/state", null).expectStatus(403);
        dav("PROPFIND", "/cluster/uploads", null, "Depth", "0").expectStatus(403);
        assertTrue(!dav("PROPFIND", "/cluster/", null, "Depth", "1").body().contains("uploads"));
    }

    @Test
    void onACaseInsensitiveStoreEverySpellingOfDavIsKept() throws Exception {
        FileSystem mac = Jimfs.newFileSystem(Configuration.osX().toBuilder().setAttributeViews("basic", "user").build());
        Path files = Files.createDirectories(mac.getPath("/m"));
        FlashTest other = FlashTest.of(() -> App.create().install(new DavModule()).any("/m/**", new Clustered(files)));
        try {
            other.request().body("x").put("/m/a.txt").expectStatus(201);
            other.request().send("MKCOL", "/m/.DAV").expectStatus(403);
            other.request().send("GET", "/m/.Dav/state").expectStatus(403);
        } finally {
            other.afterAll(null);
        }
    }

    @Test
    void aDavDirectoryTheModuleDidNotMakeIsLeftAlone() throws IOException {
        Path theirs = Files.createDirectories(root.resolve("theirs/.dav"));
        Files.writeString(theirs.resolve("notes.txt"), "mine");
        dav("PROPFIND", "/theirs/", null, "Depth", "0").expectStatus(500);
        assertEquals("mine", Files.readString(theirs.resolve("notes.txt")));
        assertTrue(Files.notExists(theirs.resolve("state")));
    }

    /** Without state of its own in .dav, .dav holds nothing of the server's: it is the client's like any name. */
    @Test
    void aLocalDirectoryServesDotDavAsAnyCollection() throws IOException {
        Files.writeString(Files.createDirectories(root.resolve("plain/.dav")).resolve("notes.txt"), "mine");
        assertTrue(dav("PROPFIND", "/plain/", null, "Depth", "1").expectStatus(207).body().contains("<D:href>/plain/.dav/</D:href>"));
        dav("GET", "/plain/.dav/notes.txt", null).expectStatus(200).expectBody("mine");
        dav("DELETE", "/plain/.dav", null).expectStatus(204);
    }

    /** A clustered directory that shows its state still keeps it there: clients see .dav and what is in it. */
    @Test
    void aClusteredDirectoryCanShowItsState() {
        dav("PUT", "/open/a.txt", "a").expectStatus(201);
        assertTrue(dav("PROPFIND", "/open/", null, "Depth", "1").body().contains("<D:href>/open/.dav/</D:href>"));
        dav("PROPFIND", "/open/.dav/state", null, "Depth", "0").expectStatus(207);
    }

    @Test
    void theBootNamesAMountWithoutItsSubtreeAMethodRouteAndAMissingModule() {
        String subtree = assertThrows(IllegalStateException.class, () -> FlashTest.of(() -> App.create()
                .install(new DavModule()).any("/files", new Archive())).port()).getMessage();
        assertTrue(subtree.contains("Archive at /files: a WebDAV mount covers a subtree; route it at /files/**"), subtree);
        String method = assertThrows(IllegalStateException.class, () -> FlashTest.of(() -> App.create()
                .install(new DavModule()).route(HttpMethod.GET, "/files/**", new Archive())).port()).getMessage();
        assertTrue(method.contains("route it for every method, with @Dav or app.any"), method);
        String module = assertThrows(IllegalStateException.class, () -> FlashTest.of(() -> App.create()
                .any("/files/**", new Archive())).port()).getMessage();
        assertTrue(module.contains("Archive is a DavHandler: install new DavModule()"), module);
        assertThrows(IllegalArgumentException.class, () -> new DavModule().maxLockTimeout(Duration.ofSeconds(599)));
    }
}
