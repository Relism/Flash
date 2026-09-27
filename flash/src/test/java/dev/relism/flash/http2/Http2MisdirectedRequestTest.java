package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.FrameWriteBuffer;
import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.flash.http2.hpack.HpackEncoder;
import dev.relism.flash.tls.TestKeystores;
import dev.relism.flash.tls.TlsConfig;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Path;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Http2MisdirectedRequestTest {
  private App app;
  private Server server;

  @AfterEach
  void stop() {
    if (server != null) server.stop().join();
  }

  @Test
  void authorityOutsideSelectedCertificateReceives421(@TempDir Path directory) throws Exception {
    Path keystore =
        TestKeystores.build(
            directory,
            "misdirected.p12",
            "changeit",
            TestKeystores.Entry.of("server", "localhost", "localhost"));
    ServerConfig config1 = ServerConfig.builder()
                .host("127.0.0.1")
                .port(0)
                .tls(TlsConfig.keystore(keystore, "changeit"))
                .http2Enabled(true)
                .build();
    app = App.create();
    app.get("/", (request, response) -> "must not run");
    server = Server.start(app, config1);
    int port = server.port();

    try (SSLSocket socket =
        (SSLSocket)
            TestKeystores.trustAllClientContext()
                .getSocketFactory()
                .createSocket("localhost", port)) {
      SSLParameters parameters = socket.getSSLParameters();
      parameters.setApplicationProtocols(new String[] {"h2"});
      socket.setSSLParameters(parameters);
      socket.startHandshake();
      socket.getOutputStream().write(request("other.example"));
      assertEquals(421, readStatus(socket.getInputStream()));
    }
  }

  private static byte[] request(String authority) {
    ByteWriter bytes = new ByteWriter(128);
    bytes.writeBytes(Http2Preface.clientPreface());
    FrameWriteBuffer frames = new FrameWriteBuffer(bytes);
    frames.beginFrame(FrameType.SETTINGS, 0, 0);
    frames.endFrame();
    frames.beginFrame(
        FrameType.HEADERS, FrameFlags.END_HEADERS | FrameFlags.END_STREAM, 1);
    HpackEncoder.writeIndexed(bytes, 2);
    HpackEncoder.writeIndexed(bytes, 7);
    HpackEncoder.writeLiteralWithNameIndex(
        bytes, 1, authority.getBytes(java.nio.charset.StandardCharsets.US_ASCII), false);
    HpackEncoder.writeIndexed(bytes, 4);
    frames.endFrame();
    byte[] result = new byte[bytes.length()];
    System.arraycopy(bytes.array(), 0, result, 0, result.length);
    return result;
  }

  private static int readStatus(InputStream input) throws Exception {
    HpackDecoder decoder = new HpackDecoder();
    byte[] header = new byte[9];
    while (true) {
      input.readNBytes(header, 0, header.length);
      int length = ((header[0] & 0xff) << 16) | ((header[1] & 0xff) << 8) | (header[2] & 0xff);
      int type = header[3] & 0xff;
      int streamId =
          ((header[5] & 0x7f) << 24)
              | ((header[6] & 0xff) << 16)
              | ((header[7] & 0xff) << 8)
              | (header[8] & 0xff);
      byte[] payload = input.readNBytes(length);
      if (type != FrameType.HEADERS.code() || streamId != 1) continue;
      int[] status = {0};
      decoder.decode(
          payload,
          0,
          payload.length,
          (name, value, never) -> {
            if (name.length() == 7 && name.byteAt(0) == ':') {
              status[0] =
                  (value.byteAt(0) - '0') * 100
                      + (value.byteAt(1) - '0') * 10
                      + value.byteAt(2)
                      - '0';
            }
          });
      return status[0];
    }
  }

}
