package dev.relism.flash.ext.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolArgumentsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolArguments of(String json) throws Exception {
        return new ToolArguments(MAPPER.readTree(json));
    }

    @Test
    void readsTypedFields() throws Exception {
        ToolArguments args = of("{\"city\":\"Rome\",\"days\":3,\"temp\":21.5,\"metric\":true}");

        assertEquals("Rome", args.getString("city"));
        assertEquals(3, args.getInt("days"));
        assertEquals(21.5, args.getDouble("temp"));
        assertTrue(args.getBoolean("metric"));
        assertTrue(args.has("city"));
        assertFalse(args.has("missing"));
    }

    @Test
    void missingFieldsFallBackToDefaults() throws Exception {
        ToolArguments args = of("{}");

        assertNull(args.getString("missing"));
        assertEquals("fallback", args.getString("missing", "fallback"));
        assertEquals(0, args.getInt("missing"));
        assertEquals(42, args.getInt("missing", 42));
        assertFalse(args.getBoolean("missing"));
    }

    @Test
    void nullArgumentsNodeBehavesAsEmpty() {
        ToolArguments args = new ToolArguments(null);
        assertFalse(args.has("anything"));
        assertNull(args.getString("anything"));
    }
}
