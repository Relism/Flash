package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.websocket.WebSocketFrame;
import dev.relism.flash.websocket.WebSocketHandler;
import dev.relism.flash.websocket.WebSocketSession;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WebSocketOverH2Test {
  private App app;
  private Server server;

  @AfterEach
  void stop() {
    if (server != null) server.stop().join();
  }

  @Test
  void opensEchoesFragmentsAndCarriesAMessageLargerThanTheFlowWindow() throws Exception {
    ServerConfig config1 = ServerConfig.builder()
                .host("127.0.0.1")
                .port(0)
                .http2CleartextEnabled(true)
                .wsFrameBufferSize(2 * 1024 * 1024)
                .build();
    app = App.create();
    app.ws(
        "/chat",
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
    server = Server.start(app, config1);
    int port = server.port();

    try (H2WebSocketTestClient client =
        new H2WebSocketTestClient("127.0.0.1", port, "/chat")) {
      assertTrue(client.connectProtocolAdvertised());

      client.sendFragmentedText("hel", "lo");
      assertArrayEquals(
          "hello".getBytes(StandardCharsets.UTF_8),
          client.readMessage(WebSocketFrame.OP_TEXT));

      byte[] large = new byte[Http2Limits.INITIAL_WINDOW_SIZE_LOCAL + 128 * 1024 + 17];
      for (int i = 0; i < large.length; i++) large[i] = (byte) (i * 31);
      client.sendBinary(large);
      assertArrayEquals(large, client.readMessage(WebSocketFrame.OP_BINARY));

      client.closeGracefully();
    }
  }

}
