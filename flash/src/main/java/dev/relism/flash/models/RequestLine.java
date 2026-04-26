package dev.relism.flash.models;

import dev.relism.fpr.core.ByteView;
import dev.relism.flash.http.HttpMethod;
import lombok.ToString;
import lombok.Value;

@ToString
@Value
public class RequestLine {
    HttpMethod method;
    ByteView   path;
    /** Raw query string bytes (after {@code ?}), {@code null} if the URI has no query string. */
    ByteView   query;
    ByteView   protocol;
    HeaderMap  headers;
}
