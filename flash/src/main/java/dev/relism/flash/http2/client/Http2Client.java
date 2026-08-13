package dev.relism.flash.http2.client;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.bytes.Pairs;
import dev.relism.flash.http.HopByHopHeaders;
import dev.relism.flash.http.HopByHopHeaders.Protocol;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http2.Http2Exception;
import dev.relism.flash.http2.Http2Limits;
import dev.relism.flash.http2.Http2Preface;
import dev.relism.flash.http2.Http2Settings;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameHeader;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.FrameWriteBuffer;
import dev.relism.flash.http2.frame.Http2FrameReader;
import dev.relism.flash.http2.frame.Http2FrameWriter;
import dev.relism.flash.http2.frame.Padding;
import dev.relism.flash.http2.frame.WriteIntent;
import dev.relism.flash.http2.hpack.ContinuationAssembler;
import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.flash.http2.hpack.HpackEncoder;
import dev.relism.flash.models.EmptyHeaderView;
import dev.relism.flash.models.HeaderView;
import dev.relism.flash.models.MutableHeaderMap;
import dev.relism.flash.transport.BufferedByteSource;
import dev.relism.fpr.core.ByteView;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Small pooled HTTP/2 client for Flash proxy handlers. It intentionally exposes synchronous
 * request/response exchange rather than trying to be a general-purpose client API.
 */
public final class Http2Client implements Closeable {
  private static final int CONNECT_TIMEOUT_MS = 10_000;
  private static final int MAX_RESPONSE_BODY_SIZE = Http2Limits.MAX_REQUEST_BODY_SIZE;

  private final ConcurrentHashMap<Origin, Connection> connections = new ConcurrentHashMap<>();
  private final SSLContext sslContext;

  public Http2Client() {
    this(null);
  }

  public Http2Client(SSLContext sslContext) {
    this.sslContext = sslContext;
  }

  public Http2ClientResponse get(URI uri) throws IOException {
    return exchange(
        uri,
        HttpMethod.GET,
        EmptyHeaderView.INSTANCE,
        new byte[0],
        EmptyHeaderView.INSTANCE);
  }

  public Http2ClientResponse exchange(
      URI uri, HttpMethod method, HeaderView headers, byte[] body, HeaderView trailers)
      throws IOException {
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(headers, "headers");
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(trailers, "trailers");
    Origin origin = Origin.from(uri);
    Connection connection;
    try {
      connection = connections.computeIfAbsent(origin, this::openUnchecked);
    } catch (OpenFailure failure) {
      throw failure.io;
    }
    try {
      return connection.exchange(uri, method, headers, body, trailers);
    } catch (IOException | RuntimeException failure) {
      connections.remove(origin, connection);
      connection.close();
      throw failure;
    }
  }

  @Override
  public void close() {
    for (Connection connection : connections.values()) connection.close();
    connections.clear();
  }

  /** Number of currently pooled origin connections. */
  public int pooledConnectionCount() {
    return connections.size();
  }

  private Connection openUnchecked(Origin origin) {
    try {
      return new Connection(origin, sslContext);
    } catch (IOException failure) {
      throw new OpenFailure(failure);
    }
  }

  private static final class Connection implements Closeable {
    private final Socket socket;
    private final OutputStream output;
    private final Http2FrameReader reader;
    private final Http2FrameWriter writer;
    private final Http2Settings peerSettings = new Http2Settings();
    private final HpackDecoder decoder = new HpackDecoder();
    private final ContinuationAssembler headers = new ContinuationAssembler();
    private final ByteWriter outgoing = new ByteWriter(16 * 1024);
    private final FrameWriteBuffer frames = new FrameWriteBuffer(outgoing);
    private final BufferIntent intent = new BufferIntent();
    private int nextStreamId = 1;
    private int connectionSendWindow = Http2Settings.DEFAULT_INITIAL_WINDOW_SIZE;
    private int streamSendWindow;
    private boolean headerEndStream;
    private boolean closed;

    Connection(Origin origin, SSLContext sslContext) throws IOException {
      socket = connect(origin, sslContext);
      output = socket.getOutputStream();
      reader =
          new Http2FrameReader(new BufferedByteSource(socket.getInputStream(), socket));
      writer = new Http2FrameWriter(output::write);
      writePreface();
      awaitServerSettings();
    }

    synchronized Http2ClientResponse exchange(
        URI uri, HttpMethod method, HeaderView requestHeaders, byte[] body, HeaderView trailers)
        throws IOException {
      if (closed) throw new IOException("HTTP/2 connection is closed");
      if (nextStreamId <= 0) throw new IOException("HTTP/2 stream id space exhausted");
      int streamId = nextStreamId;
      nextStreamId += 2;
      streamSendWindow = peerSettings.initialWindowSize();
      Exchange exchange = new Exchange(streamId);

      writeRequestHeaders(uri, method, requestHeaders, body.length == 0 && trailers.count() == 0,
          streamId);
      if (body.length != 0) writeRequestBody(exchange, body, trailers.count() == 0);
      if (trailers.count() != 0) writeRequestTrailers(trailers, streamId);
      while (!exchange.complete) readFrame(exchange);
      return exchange.response();
    }

    private void writePreface() throws IOException {
      output.write(Http2Preface.clientPreface());
      outgoing.reset();
      frames.beginFrame(FrameType.SETTINGS, 0, 0);
      outgoing.writeUInt16(Http2Settings.ENABLE_PUSH);
      outgoing.writeUInt32(0);
      outgoing.writeUInt16(Http2Settings.INITIAL_WINDOW_SIZE);
      outgoing.writeUInt32(Http2Limits.INITIAL_WINDOW_SIZE_LOCAL);
      frames.endFrame();
      frames.beginFrame(FrameType.WINDOW_UPDATE, 0, 0);
      outgoing.writeUInt31(
          Http2Limits.CONNECTION_WINDOW_SIZE_LOCAL - Http2Settings.DEFAULT_INITIAL_WINDOW_SIZE);
      frames.endFrame();
      writeOutgoing();
    }

    private void awaitServerSettings() throws IOException {
      boolean received = false;
      while (!received) {
        FrameHeader frame = reader.readFrame();
        if (frame == null) throw new IOException("server closed before SETTINGS");
        try {
          if (frame.type() == FrameType.SETTINGS && !FrameFlags.isAck(frame.flags())) {
            applySettings(frame);
            sendEmpty(FrameType.SETTINGS, FrameFlags.ACK, 0);
            received = true;
          } else if (frame.type() == FrameType.WINDOW_UPDATE) {
            applyWindowUpdate(frame, 0);
          } else if (frame.type() == FrameType.GOAWAY) {
            throw new IOException("server sent GOAWAY during HTTP/2 setup");
          }
        } finally {
          reader.consumeFrame();
        }
      }
    }

    private void writeRequestHeaders(
        URI uri, HttpMethod method, HeaderView source, boolean endStream, int streamId)
        throws IOException {
      outgoing.reset();
      frames.beginFrame(
          FrameType.HEADERS,
          FrameFlags.END_HEADERS | (endStream ? FrameFlags.END_STREAM : 0),
          streamId);
      writeMethod(method);
      HpackEncoder.writeIndexed(outgoing, "https".equalsIgnoreCase(uri.getScheme()) ? 7 : 6);
      writeAuthority(uri);
      writePath(uri);
      source.forEach(
          (name, value) -> {
            if (HopByHopHeaders.shouldForward(
                source, name, value, Protocol.HTTP_1_1, Protocol.HTTP_2)
                && !equalsAscii(name, "host")) {
              HpackEncoder.writeLiteral(outgoing, name, value);
            }
          });
      frames.endFrame();
      writeOutgoing();
    }

    private void writeRequestBody(Exchange exchange, byte[] body, boolean endStream)
        throws IOException {
      int offset = 0;
      while (offset < body.length) {
        while (connectionSendWindow <= 0 || streamSendWindow <= 0) readFrame(exchange);
        int count =
            Math.min(
                body.length - offset,
                Math.min(
                    peerSettings.maxFrameSize(),
                    Math.min(connectionSendWindow, streamSendWindow)));
        outgoing.reset();
        frames.beginFrame(
            FrameType.DATA,
            endStream && offset + count == body.length ? FrameFlags.END_STREAM : 0,
            exchange.streamId);
        outgoing.writeBytes(body, offset, count);
        frames.endFrame();
        writeOutgoing();
        connectionSendWindow -= count;
        streamSendWindow -= count;
        offset += count;
      }
    }

    private void writeRequestTrailers(HeaderView trailers, int streamId) throws IOException {
      outgoing.reset();
      frames.beginFrame(
          FrameType.HEADERS, FrameFlags.END_HEADERS | FrameFlags.END_STREAM, streamId);
      trailers.forEach(
          (name, value) -> {
            if (HopByHopHeaders.shouldForward(
                trailers, name, value, Protocol.HTTP_1_1, Protocol.HTTP_2)) {
              HpackEncoder.writeLiteral(outgoing, name, value);
            }
          });
      frames.endFrame();
      writeOutgoing();
    }

    private void readFrame(Exchange exchange) throws IOException {
      FrameHeader frame = reader.readFrame();
      if (frame == null) throw new IOException("server closed an active HTTP/2 exchange");
      try {
        FrameType type = frame.type();
        if (type == null) return;
        switch (type) {
          case SETTINGS -> {
            if (!FrameFlags.isAck(frame.flags())) {
              applySettings(frame);
              sendEmpty(FrameType.SETTINGS, FrameFlags.ACK, 0);
            }
          }
          case WINDOW_UPDATE -> applyWindowUpdate(frame, exchange.streamId);
          case PING -> {
            if (!FrameFlags.isAck(frame.flags())) sendPingAck(frame);
          }
          case HEADERS, CONTINUATION -> receiveHeaders(frame, exchange);
          case DATA -> receiveData(frame, exchange);
          case RST_STREAM -> receiveReset(frame, exchange);
          case GOAWAY -> throw receiveGoAway(frame);
          case PUSH_PROMISE -> throw new IOException("server sent PUSH_PROMISE after ENABLE_PUSH=0");
          default -> {
            // PRIORITY and unknown extension semantics do not affect this single exchange.
          }
        }
      } finally {
        reader.consumeFrame();
      }
    }

    private void receiveHeaders(FrameHeader frame, Exchange exchange) throws IOException {
      if (frame.streamId() != exchange.streamId) {
        throw new IOException("unexpected response stream " + frame.streamId());
      }
      if (frame.type() == FrameType.HEADERS) {
        if (headers.isActive()) throw new IOException("interleaved response header block");
        headerEndStream = FrameFlags.isEndStream(frame.flags());
        long unpadded =
            Padding.unpad(
                frame.buffer(),
                frame.payloadOffset(),
                frame.length(),
                FrameFlags.isPadded(frame.flags()));
        int offset = Pairs.hi(unpadded);
        int length = Pairs.lo(unpadded);
        if (FrameFlags.hasPriority(frame.flags())) {
          if (length < 5) throw new IOException("truncated response priority fields");
          offset += 5;
          length -= 5;
        }
        headers.begin(
            frame.streamId(),
            frame.buffer(),
            offset,
            length,
            FrameFlags.isEndHeaders(frame.flags()));
      } else {
        headers.continuation(
            frame.streamId(),
            frame.buffer(),
            frame.payloadOffset(),
            frame.length(),
            FrameFlags.isEndHeaders(frame.flags()));
      }
      if (!headers.isComplete()) return;

      boolean trailers = exchange.statusCode != 0;
      ResponseHeaderSink sink = new ResponseHeaderSink(exchange, trailers);
      decoder.decode(headers.buffer(), 0, headers.length(), sink);
      headers.reset();
      sink.validate();
      if (!trailers && exchange.statusCode >= 100 && exchange.statusCode < 200) {
        if (headerEndStream) throw new IOException("informational response ended the stream");
        exchange.statusCode = 0;
        exchange.headers.reset();
        return;
      }
      if (trailers && !headerEndStream) {
        throw new IOException("response trailers did not end the stream");
      }
      if (headerEndStream) exchange.complete = true;
    }

    private void receiveData(FrameHeader frame, Exchange exchange) throws IOException {
      if (frame.streamId() != exchange.streamId || exchange.statusCode == 0) {
        throw new IOException("DATA received before response headers");
      }
      long unpadded =
          Padding.unpad(
              frame.buffer(),
              frame.payloadOffset(),
              frame.length(),
              FrameFlags.isPadded(frame.flags()));
      int dataOffset = Pairs.hi(unpadded);
      int dataLength = Pairs.lo(unpadded);
      if (exchange.body.size() > MAX_RESPONSE_BODY_SIZE - dataLength) {
        throw new IOException("proxied HTTP/2 response body exceeds limit");
      }
      exchange.body.write(frame.buffer(), dataOffset, dataLength);
      if (frame.length() != 0) {
        sendWindowUpdate(0, frame.length());
        sendWindowUpdate(exchange.streamId, frame.length());
      }
      if (FrameFlags.isEndStream(frame.flags())) exchange.complete = true;
    }

    private void receiveReset(FrameHeader frame, Exchange exchange) throws IOException {
      if (frame.streamId() != exchange.streamId || frame.length() != 4) return;
      int code = readInt(frame.buffer(), frame.payloadOffset());
      throw new IOException("upstream reset HTTP/2 stream with error " + code);
    }

    private IOException receiveGoAway(FrameHeader frame) {
      closed = true;
      int code = frame.length() >= 8 ? readInt(frame.buffer(), frame.payloadOffset() + 4) : -1;
      return new IOException("upstream sent GOAWAY with error " + code);
    }

    private void applySettings(FrameHeader frame) {
      int oldWindow = peerSettings.initialWindowSize();
      peerSettings.apply(frame.buffer(), frame.payloadOffset(), frame.length(), delta -> {});
      streamSendWindow += peerSettings.initialWindowSize() - oldWindow;
    }

    private void applyWindowUpdate(FrameHeader frame, int activeStreamId) throws IOException {
      if (frame.length() != 4) throw new IOException("invalid WINDOW_UPDATE length");
      int increment = readInt(frame.buffer(), frame.payloadOffset()) & 0x7fff_ffff;
      if (increment == 0) throw new IOException("zero WINDOW_UPDATE increment");
      if (frame.streamId() == 0) connectionSendWindow = addWindow(connectionSendWindow, increment);
      else if (frame.streamId() == activeStreamId) streamSendWindow = addWindow(streamSendWindow, increment);
    }

    private void sendPingAck(FrameHeader frame) throws IOException {
      outgoing.reset();
      frames.beginFrame(FrameType.PING, FrameFlags.ACK, 0);
      outgoing.writeBytes(frame.buffer(), frame.payloadOffset(), frame.length());
      frames.endFrame();
      writeOutgoing();
    }

    private void sendWindowUpdate(int streamId, int increment) throws IOException {
      outgoing.reset();
      frames.beginFrame(FrameType.WINDOW_UPDATE, 0, streamId);
      outgoing.writeUInt31(increment);
      frames.endFrame();
      writeOutgoing();
    }

    private void sendEmpty(FrameType type, int flags, int streamId) throws IOException {
      outgoing.reset();
      frames.beginFrame(type, flags, streamId);
      frames.endFrame();
      writeOutgoing();
    }

    private void writeOutgoing() throws IOException {
      intent.reset(outgoing.array(), outgoing.length());
      writer.write(intent);
    }

    private void writeMethod(HttpMethod method) {
      if (method == HttpMethod.GET) HpackEncoder.writeIndexed(outgoing, 2);
      else if (method == HttpMethod.POST) HpackEncoder.writeIndexed(outgoing, 3);
      else {
        byte[] value = method.name().getBytes(StandardCharsets.US_ASCII);
        HpackEncoder.writeLiteralWithNameIndex(outgoing, 2, value, false);
      }
    }

    private void writeAuthority(URI uri) {
      String authority = uri.getRawAuthority();
      if (authority == null || authority.isEmpty()) {
        throw new IllegalArgumentException("HTTP/2 URI requires an authority");
      }
      HpackEncoder.writeLiteralWithNameIndex(
          outgoing, 1, authority.getBytes(StandardCharsets.US_ASCII), false);
    }

    private void writePath(URI uri) {
      String path = uri.getRawPath();
      if (path == null || path.isEmpty()) path = "/";
      if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
      if ("/".equals(path)) HpackEncoder.writeIndexed(outgoing, 4);
      else if ("/index.html".equals(path)) HpackEncoder.writeIndexed(outgoing, 5);
      else {
        HpackEncoder.writeLiteralWithNameIndex(
            outgoing, 4, path.getBytes(StandardCharsets.US_ASCII), false);
      }
    }

    @Override
    public synchronized void close() {
      if (closed) return;
      closed = true;
      writer.close();
      try {
        socket.close();
      } catch (IOException ignored) {
        // Closing a broken pooled connection is best-effort.
      }
    }

    private static Socket connect(Origin origin, SSLContext sslContext) throws IOException {
      if (!origin.secure) {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(origin.host, origin.port), CONNECT_TIMEOUT_MS);
        return socket;
      }
      SSLContext context;
      try {
        context = sslContext == null ? SSLContext.getDefault() : sslContext;
      } catch (Exception failure) {
        throw new IOException("cannot initialize TLS context", failure);
      }
      SSLSocket socket =
          (SSLSocket) context.getSocketFactory().createSocket(origin.host, origin.port);
      SSLParameters parameters = socket.getSSLParameters();
      parameters.setApplicationProtocols(new String[] {"h2"});
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      socket.setSSLParameters(parameters);
      socket.startHandshake();
      if (!"h2".equals(socket.getApplicationProtocol())) {
        socket.close();
        throw new IOException("upstream did not negotiate HTTP/2 through ALPN");
      }
      return socket;
    }
  }

  private static final class Exchange {
    private final int streamId;
    private final MutableHeaderMap headers = new MutableHeaderMap();
    private final MutableHeaderMap trailers = new MutableHeaderMap();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private int statusCode;
    private boolean complete;

    private Exchange(int streamId) {
      this.streamId = streamId;
    }

    private Http2ClientResponse response() {
      return new Http2ClientResponse(statusCode, headers, body.toByteArray(), trailers);
    }
  }

  private static final class ResponseHeaderSink
      implements dev.relism.flash.http2.hpack.HeaderSink {
    private final Exchange exchange;
    private final boolean trailers;
    private boolean regular;
    private boolean status;

    private ResponseHeaderSink(Exchange exchange, boolean trailers) {
      this.exchange = exchange;
      this.trailers = trailers;
    }

    @Override
    public void accept(ByteView name, ByteView value, boolean neverIndexed) {
      if (name.length() != 0 && name.byteAt(0) == ':') {
        if (trailers || regular || status || !equalsAscii(name, ":status")) {
          throw Http2Exception.PROTOCOL_ERROR;
        }
        exchange.statusCode = parseStatus(value);
        status = true;
        return;
      }
      regular = true;
      MutableHeaderMap target = trailers ? exchange.trailers : exchange.headers;
      byte[] nameBytes = copy(name);
      byte[] valueBytes = copy(value);
      target.add(nameBytes, 0, nameBytes.length, valueBytes, 0, valueBytes.length);
    }

    private void validate() throws IOException {
      if (!trailers && !status) throw new IOException("HTTP/2 response omitted :status");
    }

    private static int parseStatus(ByteView value) {
      if (value.length() != 3) throw Http2Exception.PROTOCOL_ERROR;
      int code = 0;
      for (int i = 0; i < 3; i++) {
        int digit = (value.byteAt(i) & 0xff) - '0';
        if (digit < 0 || digit > 9) throw Http2Exception.PROTOCOL_ERROR;
        code = code * 10 + digit;
      }
      return code;
    }
  }

  private static final class BufferIntent implements WriteIntent {
    private byte[] bytes;
    private int length;
    private WriteIntent next;

    private void reset(byte[] bytes, int length) {
      this.bytes = bytes;
      this.length = length;
      this.next = null;
    }

    @Override public byte[] buffer() { return bytes; }
    @Override public int offset() { return 0; }
    @Override public int length() { return length; }
    @Override public WriteIntent mpscNext() { return next; }
    @Override public void setMpscNext(WriteIntent next) { this.next = next; }
  }

  private record Origin(String scheme, String host, int port, boolean secure) {
    private static Origin from(URI uri) {
      String scheme = uri.getScheme();
      boolean secure;
      if ("https".equalsIgnoreCase(scheme)) secure = true;
      else if ("http".equalsIgnoreCase(scheme)) secure = false;
      else throw new IllegalArgumentException("HTTP/2 URI scheme must be http or https");
      if (uri.getHost() == null) throw new IllegalArgumentException("HTTP/2 URI requires a host");
      int port = uri.getPort() >= 0 ? uri.getPort() : secure ? 443 : 80;
      return new Origin(scheme.toLowerCase(), uri.getHost(), port, secure);
    }
  }

  private static final class OpenFailure extends RuntimeException {
    private final IOException io;

    private OpenFailure(IOException io) {
      super(io);
      this.io = io;
    }
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

  private static byte[] copy(ByteView view) {
    byte[] result = new byte[view.length()];
    for (int i = 0; i < result.length; i++) result[i] = view.byteAt(i);
    return result;
  }

  private static int addWindow(int current, int increment) throws IOException {
    long next = (long) current + increment;
    if (next > Integer.MAX_VALUE) throw new IOException("HTTP/2 flow-control window overflow");
    return (int) next;
  }

  private static int readInt(byte[] bytes, int offset) {
    return ((bytes[offset] & 0xff) << 24)
        | ((bytes[offset + 1] & 0xff) << 16)
        | ((bytes[offset + 2] & 0xff) << 8)
        | (bytes[offset + 3] & 0xff);
  }
}
