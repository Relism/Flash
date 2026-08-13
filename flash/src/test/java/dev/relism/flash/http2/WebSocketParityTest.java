package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.websocket.WebSocketFrame;
import dev.relism.flash.websocket.WebSocketHandler;
import dev.relism.flash.websocket.WebSocketSession;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WebSocketParityTest {
  private FlashApp app;

  @AfterEach
  void stop() {
    if (app != null) app.stop().join();
  }

  @Test
  void oneRouteAndHandlerEchoTheSameMessageOverHttp1AndHttp2() throws Exception {
    int port = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(port)
                .http2CleartextEnabled(true)
                .build());
    app.ws(
        "/parity",
        new WebSocketHandler() {
          @Override
          public void onOpen(WebSocketSession session) {}

          @Override
          public void onMessage(WebSocketSession session, WebSocketFrame frame) {
            try {
              session.echo(frame);
            } catch (Exception failure) {
              throw new RuntimeException(failure);
            }
          }
        });
    app.start();

    byte[] expected = "same-handler".getBytes(StandardCharsets.UTF_8);
    byte[] overHttp1 = exchangeOverHttp1(port, expected);
    byte[] overHttp2;
    try (H2WebSocketTestClient client =
        new H2WebSocketTestClient("127.0.0.1", port, "/parity")) {
      client.sendText(new String(expected, StandardCharsets.UTF_8));
      overHttp2 = client.readMessage(WebSocketFrame.OP_TEXT);
      client.closeGracefully();
    }

    assertArrayEquals(expected, overHttp1);
    assertArrayEquals(overHttp1, overHttp2);
  }

  private static byte[] exchangeOverHttp1(int port, byte[] payload) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5_000);
      InputStream input = socket.getInputStream();
      OutputStream output = socket.getOutputStream();
      String key =
          Base64.getEncoder()
              .encodeToString("flash-parity-key".getBytes(StandardCharsets.US_ASCII));
      String request =
          "GET /parity HTTP/1.1\r\n"
              + "Host: 127.0.0.1:"
              + port
              + "\r\nUpgrade: websocket\r\n"
              + "Connection: Upgrade\r\nSec-WebSocket-Key: "
              + key
              + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
      output.write(request.getBytes(StandardCharsets.US_ASCII));
      output.flush();
      assertTrue(readHeaders(input).startsWith("HTTP/1.1 101 Switching Protocols"));

      output.write(maskedFrame(WebSocketFrame.OP_TEXT, payload));
      output.flush();
      byte[] echoed = readServerFrame(input, WebSocketFrame.OP_TEXT);
      output.write(maskedFrame(WebSocketFrame.OP_CLOSE, new byte[] {3, (byte) 232}));
      output.flush();
      return echoed;
    }
  }

  private static String readHeaders(InputStream input) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int previous3 = -1;
    int previous2 = -1;
    int previous1 = -1;
    int current;
    while ((current = input.read()) >= 0) {
      bytes.write(current);
      if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
        break;
      }
      previous3 = previous2;
      previous2 = previous1;
      previous1 = current;
    }
    return bytes.toString(StandardCharsets.US_ASCII);
  }

  private static byte[] maskedFrame(byte opcode, byte[] payload) {
    byte[] encoded = new byte[6 + payload.length];
    encoded[0] = (byte) (0x80 | opcode);
    encoded[1] = (byte) (0x80 | payload.length);
    byte[] mask = {1, 2, 3, 4};
    System.arraycopy(mask, 0, encoded, 2, mask.length);
    for (int i = 0; i < payload.length; i++) {
      encoded[6 + i] = (byte) (payload[i] ^ mask[i & 3]);
    }
    return encoded;
  }

  private static byte[] readServerFrame(InputStream input, byte expectedOpcode) throws Exception {
    byte[] header = input.readNBytes(2);
    if (header.length != 2 || (header[0] & 0x0f) != expectedOpcode) {
      throw new AssertionError("unexpected WebSocket response frame");
    }
    int length = header[1] & 0x7f;
    return input.readNBytes(length);
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
