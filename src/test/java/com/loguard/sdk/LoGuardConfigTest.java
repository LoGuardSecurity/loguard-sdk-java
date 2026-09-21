package com.loguard.sdk;

import com.loguard.sdk.exceptions.LoGuardAuthException;
import com.loguard.sdk.exceptions.LoGuardValidationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class LoGuardConfigTest {

    @Test
    void requiresApiKey() {
        assertThrows(LoGuardAuthException.class, () -> LoGuardConfig.builder("").build());
    }

    @Test
    void requiresApiKeyNotJustWhitespace() {
        assertThrows(LoGuardAuthException.class, () -> LoGuardConfig.builder("   ").build());
    }

    @Test
    void rejectsPlainHttpByDefault() {
        assertThrows(LoGuardValidationException.class, () ->
            LoGuardConfig.builder("lg_live_x").baseUrl("http://loguard.org").build());
    }

    @Test
    void allowsPlainHttpWhenExplicitlyOptedIn() {
        LoGuardConfig config = LoGuardConfig.builder("lg_live_x")
            .baseUrl("http://loguard.internal")
            .allowInsecureTransport(true)
            .build();
        assertEquals("http://loguard.internal", config.baseUrl());
        assertTrue(config.allowInsecureTransport());
    }

    @Test
    void trailingSlashIsStripped() {
        LoGuardConfig config = LoGuardConfig.builder("lg_live_x").baseUrl("https://loguard.org/").build();
        assertEquals("https://loguard.org", config.baseUrl());
        assertEquals("https://loguard.org/v1/ingest", config.ingestUrl());
    }

    @Test
    void defaultsMatchOtherSdks() {
        LoGuardConfig config = LoGuardConfig.builder("lg_live_x").build();
        assertEquals("https://loguard.org", config.baseUrl());
        assertEquals("production", config.env());
        assertEquals(Duration.ofSeconds(10), config.timeout());
        assertEquals(3, config.retries());
    }
}
