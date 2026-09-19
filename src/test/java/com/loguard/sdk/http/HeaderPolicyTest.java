package com.loguard.sdk.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HeaderPolicyTest {

    @Test
    void forbiddenHeadersAreAlwaysStrippedEvenIfRequested() {
        List<String> sanitized = HeaderPolicy.sanitizeRequested(List.of(
            "Authorization", "Cookie", "X-Api-Key", "X-Auth-Token", "Proxy-Authorization", "Set-Cookie", "User-Agent"
        ));
        assertEquals(List.of("user-agent"), sanitized);
    }

    @Test
    void collectReturnsEmptyWhenNoHeadersRequested() {
        Map<String, String> collected = HeaderPolicy.collect(List.of(), name -> {
            throw new AssertionError("should not be called");
        });
        assertTrue(collected.isEmpty());
    }

    @Test
    void collectTruncatesOversizedValues() {
        String huge = "x".repeat(5000);
        Map<String, String> collected = HeaderPolicy.collect(List.of("user-agent"), name -> huge);
        assertEquals(512, collected.get("user-agent").length());
    }

    @Test
    void collectSkipsAbsentHeaders() {
        Map<String, String> collected = HeaderPolicy.collect(List.of("referer"), name -> null);
        assertTrue(collected.isEmpty());
    }

    @Test
    void sanitizeDedupesCaseInsensitively() {
        List<String> sanitized = HeaderPolicy.sanitizeRequested(List.of("Referer", "referer", "REFERER"));
        assertEquals(List.of("referer"), sanitized);
    }
}
