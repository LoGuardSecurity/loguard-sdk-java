package com.loguard.sdk.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    void roundTripsObjectsArraysAndScalars() {
        Map<String, Object> obj = Map.of(
            "a", 1,
            "b", "text",
            "c", true,
            "d", List.of(1, 2, 3)
        );
        String json = Json.write(obj);
        Object decoded = Json.parse(json);
        assertInstanceOf(Map.class, decoded);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) decoded;
        assertEquals("text", m.get("b"));
        assertEquals(Boolean.TRUE, m.get("c"));
    }

    @Test
    void escapesSpecialCharactersInStrings() {
        String json = Json.write(Map.of("x", "line1\nline2\t\"quoted\""));
        Object decoded = Json.parse(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) decoded;
        assertEquals("line1\nline2\t\"quoted\"", m.get("x"));
    }

    @Test
    void nullInputParsesToNull() {
        assertNull(Json.parse(null));
        assertNull(Json.parse(""));
    }

    @Test
    void malformedJsonThrowsControlledException() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{not valid json!!"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[1, 2,"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\": }"));
    }

    @Test
    void deeplyNestedInputIsRejectedNotStackOverflowed() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            sb.append('[');
        }
        // Should throw a controlled JsonException (max depth exceeded),
        // never a StackOverflowError that could crash the whole worker thread.
        assertThrows(Json.JsonException.class, () -> Json.parse(sb.toString()));
    }

    @Test
    void oversizedInputIsRejected() {
        String huge = "\"" + "a".repeat(9 * 1024 * 1024) + "\"";
        assertThrows(Json.JsonException.class, () -> Json.parse(huge));
    }

    @Test
    void trailingContentIsRejected() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1} garbage"));
    }

    @Test
    void unicodeEscapesAreDecoded() {
        Object decoded = Json.parse("{\"x\": \"caf\\u00e9\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) decoded;
        assertEquals("café", m.get("x"));
    }
}
