package dev.relism.flash.ext.webdav;

import dev.relism.flash.ext.webdav.internal.Locks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.http.HttpException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pieces with rules of their own: paths, the If header, the lock table. */
class PartsTest {

    @Test
    void aPathIsDecodedNormalizedAndKeptInsideItsMount() {
        assertEquals(List.of("a", "b c", "é"), DavPath.parse("a/./x/../b%20c//%C3%A9/").segments());
        assertEquals("/files/a/b%20c/%C3%A9", DavPath.parse("a/b%20c/%c3%a9").href("/files", false));
        assertEquals("/files/", DavPath.parse("").href("/files", true));
        assertEquals("é", DavPath.parse("e%CC%81").name(), "a decomposed name stays decomposed");

        for (String escape : List.of("..", "%2e%2e", "a/../..", "%2E%2E/etc/passwd"))
            assertEquals(403, assertThrows(HttpException.class, () -> DavPath.parse(escape)).status(), escape);
        for (String invalid : List.of("a%2Fb", "a%00", "%ZZ", "%C3", "%"))
            assertEquals(400, assertThrows(HttpException.class, () -> DavPath.parse(invalid)).status(), invalid);
    }

    @Test
    void containmentAndMovingFollowSegmentsNotText() {
        DavPath docs = DavPath.parse("docs");
        assertTrue(docs.contains(DavPath.parse("docs/a")));
        assertTrue(!docs.contains(DavPath.parse("docs2/a")));
        assertEquals(DavPath.parse("archive/a/b"), DavPath.parse("docs/a/b").moved(docs, DavPath.parse("archive")));
        assertNull(DavPath.ROOT.parent());
    }

    @Test
    void theIfHeaderReadsTaggedAndUntaggedListsNotAndEntityTags() {
        IfHeader untagged = IfHeader.parse("(<urn:uuid:a> [\"e1\"]) (Not <DAV:no-lock>)");
        assertEquals(List.of(new IfHeader.Clause(null, List.of(new IfHeader.Condition(false, "urn:uuid:a", null),
                new IfHeader.Condition(false, null, "\"e1\""))),
                new IfHeader.Clause(null, List.of(new IfHeader.Condition(true, "DAV:no-lock", null)))), untagged.clauses());
        assertEquals(List.of("urn:uuid:a"), untagged.tokens());

        IfHeader tagged = IfHeader.parse("<http://h/files/a> (<urn:uuid:b>) <http://h/files/b> ([W/\"x\"]) (Not [\"y\"])");
        assertEquals(3, tagged.clauses().size());
        assertEquals("http://h/files/b", tagged.clauses().get(2).resource());

        for (String malformed : List.of("", "<x>", "(<a>", "()", "<a> (<b>) (<c>) x", "(<a>) <b> (<c>)", "([\"x\""))
            assertEquals(400, assertThrows(HttpException.class, () -> IfHeader.parse(malformed)).status(), malformed);
    }

    @Test
    void locksConflictByScopeAndDepthAndExpire() {
        Locks locks = new Locks();
        DavPath dir = DavPath.parse("dir");
        DavPath file = DavPath.parse("dir/file");
        Locks.Lock deep = locks.acquire(dir, true, true, "", 60);
        assertEquals(List.of(deep), locks.covering(file));
        assertNull(locks.acquire(file, false, false, "", 60), "a shared lock under an exclusive one");
        locks.release(deep.token());

        Locks.Lock shared = locks.acquire(file, false, false, "", 60);
        assertTrue(locks.acquire(file, false, false, "", 60) != null, "two shared locks");
        assertNull(locks.acquire(dir, true, true, "", 60), "a deep exclusive lock over a shared one below");
        assertEquals(2, locks.within(dir).size());
        locks.releaseWithin(dir);
        assertNull(locks.get(shared.token()));

        Locks.Lock brief = locks.acquire(file, true, false, "", 0);
        locks.prune(System.currentTimeMillis());
        assertNull(locks.get(brief.token()), "a lock past its timeout is gone");

        for (int i = 0; i < Locks.MAX; i++) locks.acquire(DavPath.parse("f" + i), true, false, "", 60);
        assertEquals(503, assertThrows(HttpException.class, () -> locks.acquire(file, true, false, "", 60)).status());
    }
}
