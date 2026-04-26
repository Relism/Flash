package dev.relism.flash.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimpleHandlerTest {

    // --- execution ---

    @Test
    void handle_invokesFunctionalHandler() throws Exception {
        SimpleHandler.FunctionalHandler func = (req, res) -> "Hello";
        SimpleHandler handler = new SimpleHandler(func);
        
        Object result = handler.handle(null, null);
        assertEquals("Hello", result);
    }
}
