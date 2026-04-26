package dev.relism.flash.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathUtilsTest {

    // --- sanitize ---

    @Test
    void sanitize_nullOrBlank_returnsRoot() {
        assertEquals("/", PathUtils.sanitize(null));
        assertEquals("/", PathUtils.sanitize(""));
        assertEquals("/", PathUtils.sanitize("   "));
        assertEquals("/", PathUtils.sanitize("/"));
    }

    @Test
    void sanitize_trimsWhitespaceAndEnsuresLeadingSlash() {
        assertEquals("/users", PathUtils.sanitize(" users "));
        assertEquals("/api", PathUtils.sanitize("api"));
    }

    @Test
    void sanitize_removesTrailingSlash() {
        assertEquals("/users", PathUtils.sanitize("/users/"));
        assertEquals("/api/v1", PathUtils.sanitize("/api/v1/"));
    }

    @Test
    void sanitize_collapsesMultipleSlashes() {
        assertEquals("/a/b/c", PathUtils.sanitize("//a///b//c/"));
    }

    // --- join ---

    @Test
    void join_withRootBase_returnsSanitizedPath() {
        assertEquals("/users", PathUtils.join("/", "/users/"));
        assertEquals("/users", PathUtils.join("/", "users"));
        assertEquals("/blogs", PathUtils.join("/", "/blogs"));
    }

    @Test
    void join_withRootPath_returnsSanitizedBase() {
        assertEquals("/api", PathUtils.join("/api/", "/"));
        assertEquals("/api", PathUtils.join("api", ""));
    }

    @Test
    void join_preventsDoubleNamespace() {
        // Path already starts with base
        assertEquals("/api/users", PathUtils.join("/api", "/api/users"));
        assertEquals("/api/users", PathUtils.join("/api/", "/api/users/"));
    }

    @Test
    void join_concatenatesProperly() {
        assertEquals("/api/users", PathUtils.join("/api", "users"));
        assertEquals("/api/users", PathUtils.join("/api", "/users"));
        assertEquals("/api/users", PathUtils.join("api", "users"));
    }
}
