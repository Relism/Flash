package dev.relism.flash.http2.client;

import dev.relism.flash.models.HeaderView;

/** Complete response returned by Flash's proxy-oriented HTTP/2 client. */
public record Http2ClientResponse(
    int statusCode, HeaderView headers, byte[] body, HeaderView trailers) {}
