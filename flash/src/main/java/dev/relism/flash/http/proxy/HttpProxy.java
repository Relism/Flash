package dev.relism.flash.http.proxy;

import dev.relism.flash.http.HopByHopHeaders;
import dev.relism.flash.http.HopByHopHeaders.Protocol;
import dev.relism.flash.http2.client.Http2Client;
import dev.relism.flash.http2.client.Http2ClientResponse;
import dev.relism.flash.models.HeaderView;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;
import dev.relism.flash.models.SimpleHandler;
import dev.relism.fpr.core.ByteView;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Protocol-neutral reverse-proxy adapter backed by Flash's HTTP/2 upstream client. */
public final class HttpProxy {
  private HttpProxy() {}

  /** Creates a handler that preserves the incoming path, query, fields, body and trailers. */
  public static SimpleHandler.FunctionalHandler toHttp2(URI upstreamOrigin, Http2Client client) {
    Objects.requireNonNull(upstreamOrigin, "upstreamOrigin");
    Objects.requireNonNull(client, "client");
    return (request, response) -> relay(upstreamOrigin, client, request, response);
  }

  private static Response relay(
      URI upstreamOrigin, Http2Client client, Request request, Response response) throws Exception {
    byte[] body = request.body().bytes();
    Protocol downstream =
        request.getRequestLine().getProtocol() == null ? Protocol.HTTP_2 : Protocol.HTTP_1_1;
    URI target = upstreamOrigin.resolve(rawTarget(request));
    Http2ClientResponse upstream =
        client.exchange(
            target,
            request.method(),
            request.getRequestLine().getHeaders(),
            body,
            request.trailers());

    response.status(upstream.statusCode()).body(upstream.body());
    copyHeaders(upstream.headers(), Protocol.HTTP_2, downstream, response, false);
    copyHeaders(upstream.trailers(), Protocol.HTTP_2, downstream, response, true);
    return response;
  }

  private static String rawTarget(Request request) {
    String path = request.path();
    ByteView query = request.getRequestLine().getQuery();
    if (query == null || query.length() == 0) return path;
    byte[] bytes = new byte[query.length()];
    for (int i = 0; i < bytes.length; i++) bytes[i] = query.byteAt(i);
    return path + "?" + new String(bytes, StandardCharsets.US_ASCII);
  }

  private static void copyHeaders(
      HeaderView source,
      Protocol sourceProtocol,
      Protocol targetProtocol,
      Response response,
      boolean trailers) {
    source.forEach(
        (name, value) -> {
          if (!HopByHopHeaders.shouldForward(
              source, name, value, sourceProtocol, targetProtocol)) return;
          if (!trailers && (equalsAscii(name, "content-length") || equalsAscii(name, "content-type"))) {
            if (equalsAscii(name, "content-type")) response.type(string(value));
            return;
          }
          if (trailers) response.trailer(string(name), string(value));
          else response.header(string(name), string(value));
        });
  }

  private static String string(ByteView value) {
    byte[] bytes = new byte[value.length()];
    for (int i = 0; i < bytes.length; i++) bytes[i] = value.byteAt(i);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static boolean equalsAscii(ByteView bytes, String value) {
    if (bytes.length() != value.length()) return false;
    for (int i = 0; i < bytes.length(); i++) {
      int left = bytes.byteAt(i) & 0xff;
      int right = value.charAt(i);
      if (left >= 'A' && left <= 'Z') left += 'a' - 'A';
      if (right >= 'A' && right <= 'Z') right += 'a' - 'A';
      if (left != right) return false;
    }
    return true;
  }
}
