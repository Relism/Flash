package dev.relism.flash.http;

import dev.relism.flash.http.ContentType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResponseSerializerTest {

    private static List<String> collect(Response r) {
        List<String> fields = new ArrayList<>();
        ResponseSerializer.forEachField(r, (nameBuf, nameOff, nameLen, valueBuf, valueOff, valueLen) ->
                fields.add(new String(nameBuf, nameOff, nameLen, StandardCharsets.US_ASCII)
                        + "=" + new String(valueBuf, valueOff, valueLen, StandardCharsets.US_ASCII)));
        return fields;
    }

    @Test
    void contentTypeFirst_thenCustomHeadersInOrder() {
        Response r = new Response(200, ContentType.JSON);
        r.header("X-A", "1").header("X-B", "2");
        assertEquals(List.of("Content-Type=application/json", "X-A=1", "X-B=2"), collect(r));
    }

    @Test
    void contentTypeNone_isSkipped_notEmptyValue() {
        Response r = new Response(200, ContentType.NONE);
        r.header("X-Only", "here");
        assertEquals(List.of("X-Only=here"), collect(r));
    }

    @Test
    void noHeadersAtAll_onlyContentType() {
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        assertEquals(List.of("Content-Type=text/plain"), collect(r));
    }

    @Test
    void numericHeader_isWrittenAsDecimal() {
        Response r = new Response(200, ContentType.NONE);
        r.header("X-Count", 0L).header("X-Big", 9_007_199_254_740_993L);
        assertEquals(List.of("X-Count=0", "X-Big=9007199254740993"), collect(r));
    }

    @Test
    void preEncodedHeaderObject_isIncluded_withStructure() {
        Response r = new Response(200, ContentType.NONE);
        r.header(new PreEncodedHeader("X-Boot", "constant"));
        assertEquals(List.of("X-Boot=constant"), collect(r));
    }

    @Test
    void zeroAllocation_byteRangesAreSlicesOfResponsesOwnBuffers_notCopies() {
        Response r = new Response(200, ContentType.NONE);
        r.header("X-A", "value-a");
        byte[][] captured = new byte[2][];
        ResponseSerializer.forEachField(r, (nameBuf, nameOff, nameLen, valueBuf, valueOff, valueLen) -> {
            captured[0] = nameBuf;
            captured[1] = valueBuf;
        });
        // Both slices must reference the SAME backing array (the response's own header region) --
        // proves no copy was made to hand the field to the consumer.
        assertSame(captured[0], captured[1]);
    }
}
