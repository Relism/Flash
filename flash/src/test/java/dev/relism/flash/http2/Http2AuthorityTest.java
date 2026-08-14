package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Http2AuthorityTest {
  @Test
  void matchesExactIpPortAndSingleLabelWildcardAuthorities() {
    assertTrue(Http2Authority.matches("api.example.com:443", "api.example.com"));
    assertTrue(Http2Authority.matches("127.0.0.1:8443", "127.0.0.1"));
    assertTrue(Http2Authority.matches("one.example.com", "*.example.com"));
    assertFalse(Http2Authority.matches("example.com", "*.example.com"));
    assertFalse(Http2Authority.matches("two.one.example.com", "*.example.com"));
    assertFalse(Http2Authority.matches("other.example.net", "*.example.com"));
  }
}
