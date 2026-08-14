package dev.relism.flash.http2.hpack;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.bytes.PooledSlice;
import dev.relism.fpr.core.ByteView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class HpackDecoderTest {
  private static final HexFormat HEX = HexFormat.of();

  private static final class CollectingSink implements HeaderSink {
    final List<String> fields = new ArrayList<>();
    final List<Boolean> neverIndexed = new ArrayList<>();

    @Override
    public void accept(ByteView name, ByteView value, boolean never) {
      fields.add(text(name) + ": " + text(value));
      neverIndexed.add(never);
    }
  }

  @Test
  void appendixC2IndependentRepresentations() {
    HpackDecoder decoder = new HpackDecoder();
    CollectingSink sink = decode(decoder, "400a637573746f6d2d6b65790d637573746f6d2d686561646572");
    assertEquals(List.of("custom-key: custom-header"), sink.fields);
    assertDynamic(decoder, 1, "custom-key", "custom-header", 55);

    decoder = new HpackDecoder();
    sink = decode(decoder, "040c2f73616d706c652f70617468");
    assertEquals(List.of(":path: /sample/path"), sink.fields);
    assertEquals(0, decoder.dynamicTable().count());

    sink = decode(decoder, "100870617373776f726406736563726574");
    assertEquals(List.of("password: secret"), sink.fields);
    assertEquals(List.of(true), sink.neverIndexed);
    assertEquals(0, decoder.dynamicTable().count());

    sink = decode(decoder, "82");
    assertEquals(List.of(":method: GET"), sink.fields);
  }

  @Test
  void appendixC3RequestsWithoutHuffman() {
    verifyRequestSequence(
        "828684410f7777772e6578616d706c652e636f6d",
        "828684be58086e6f2d6361636865",
        "828785bf400a637573746f6d2d6b65790c637573746f6d2d76616c7565");
  }

  @Test
  void appendixC4RequestsWithHuffman() {
    verifyRequestSequence(
        "828684418cf1e3c2e5f23a6ba0ab90f4ff",
        "828684be5886a8eb10649cbf",
        "828785bf408825a849e95ba97d7f8925a849e95bb8e8b4bf");
  }

  @Test
  void appendixC5ResponsesWithoutHuffman() {
    verifyResponseSequence(
        "4803333032580770726976617465611d4d6f6e2c203231204f637420323031332032303a31333a323120474d546e1768747470733a2f2f7777772e6578616d706c652e636f6d",
        "4803333037c1c0bf",
        "88c1611d4d6f6e2c203231204f637420323031332032303a31333a323220474d54c05a04677a69707738666f6f3d4153444a4b48514b425a584f5157454f50495541585157454f49553b206d61782d6167653d333630303b2076657273696f6e3d31");
  }

  @Test
  void appendixC6ResponsesWithHuffman() {
    verifyResponseSequence(
        "488264025885aec3771a4b6196d07abe941054d444a8200595040b8166e082a62d1bff6e919d29ad171863c78f0b97c8e9ae82ae43d3",
        "4883640effc1c0bf",
        "88c16196d07abe941054d444a8200595040b8166e084a62d1bffc05a839bd9ab77ad94e7821dd7f2e6c7b335dfdfcd5b3960d5af27087f3672c1ab270fb5291f9587316065c003ed4ee5b1063d5007");
  }

  private static void verifyRequestSequence(String first, String second, String third) {
    HpackDecoder decoder = new HpackDecoder();
    assertEquals(
        List.of(":method: GET", ":scheme: http", ":path: /", ":authority: www.example.com"),
        decode(decoder, first).fields);
    assertDynamic(decoder, 1, ":authority", "www.example.com", 57);

    assertEquals(
        List.of(
            ":method: GET",
            ":scheme: http",
            ":path: /",
            ":authority: www.example.com",
            "cache-control: no-cache"),
        decode(decoder, second).fields);
    assertDynamic(decoder, 1, "cache-control", "no-cache", 110);
    assertDynamic(decoder, 2, ":authority", "www.example.com", 110);

    assertEquals(
        List.of(
            ":method: GET",
            ":scheme: https",
            ":path: /index.html",
            ":authority: www.example.com",
            "custom-key: custom-value"),
        decode(decoder, third).fields);
    assertDynamic(decoder, 1, "custom-key", "custom-value", 164);
    assertDynamic(decoder, 2, "cache-control", "no-cache", 164);
    assertDynamic(decoder, 3, ":authority", "www.example.com", 164);
  }

  private static void verifyResponseSequence(String first, String second, String third) {
    HpackDecoder decoder = new HpackDecoder(256, 32_768);
    assertEquals(responseFields("302", "21"), decode(decoder, first).fields);
    assertDynamic(decoder, 1, "location", "https://www.example.com", 222);
    assertDynamic(decoder, 4, ":status", "302", 222);

    assertEquals(responseFields("307", "21"), decode(decoder, second).fields);
    assertDynamic(decoder, 1, ":status", "307", 222);
    assertDynamic(decoder, 4, "cache-control", "private", 222);

    List<String> expected = new ArrayList<>(responseFields("200", "22"));
    expected.add("content-encoding: gzip");
    expected.add("set-cookie: foo=ASDJKHQKBZXOQWEOPIUAXQWEOIU; max-age=3600; version=1");
    assertEquals(expected, decode(decoder, third).fields);
    assertEquals(3, decoder.dynamicTable().count());
    assertDynamic(
        decoder, 1, "set-cookie", "foo=ASDJKHQKBZXOQWEOPIUAXQWEOIU; max-age=3600; version=1", 215);
    assertDynamic(decoder, 2, "content-encoding", "gzip", 215);
    assertDynamic(decoder, 3, "date", "Mon, 21 Oct 2013 20:13:22 GMT", 215);
  }

  private static List<String> responseFields(String status, String second) {
    return List.of(
        ":status: " + status,
        "cache-control: private",
        "date: Mon, 21 Oct 2013 20:13:" + second + " GMT",
        "location: https://www.example.com");
  }

  private static CollectingSink decode(HpackDecoder decoder, String hex) {
    CollectingSink sink = new CollectingSink();
    byte[] block = HEX.parseHex(hex);
    decoder.decode(block, 0, block.length, sink);
    return sink;
  }

  private static void assertDynamic(
      HpackDecoder decoder, int index, String expectedName, String expectedValue, int size) {
    PooledSlice name = new PooledSlice();
    PooledSlice value = new PooledSlice();
    decoder.dynamicTable().get(index, name, value);
    assertEquals(expectedName, text(name));
    assertEquals(expectedValue, text(value));
    assertEquals(size, decoder.dynamicTable().size());
  }

  private static String text(ByteView value) {
    byte[] bytes = new byte[value.length()];
    for (int i = 0; i < bytes.length; i++) bytes[i] = value.byteAt(i);
    return new String(bytes, StandardCharsets.US_ASCII);
  }
}
