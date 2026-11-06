package dev.relism.flash.ext.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedirectsTest {

    @Test
    void onlyThisOriginsPathsAreKept() {
        assertEquals("/home?tab=1", Redirects.localPath("/home?tab=1"));
        for (String elsewhere : new String[]{null, "", "https://evil.test", "//evil.test", "/\\evil.test", "home"}) {
            assertEquals("/", Redirects.localPath(elsewhere), elsewhere);
        }
    }

    /** A Location header must not carry a line break of its own, nor a tab a browser drops to read {@code //evil.test}. */
    @Test
    void controlCharactersAndNonAsciiArePercentEncoded() {
        assertEquals("/%0D%0ASet-Cookie:%20x=1", Redirects.localPath("/\r\nSet-Cookie: x=1"));
        assertEquals("/%09/evil.test", Redirects.localPath("/\t/evil.test"));
        assertEquals("/caf%C3%A9/%F0%9F%98%80", Redirects.localPath("/café/😀"));
    }
}
