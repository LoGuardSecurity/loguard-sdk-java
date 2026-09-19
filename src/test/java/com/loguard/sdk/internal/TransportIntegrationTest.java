package com.loguard.sdk.internal;

import com.loguard.sdk.exceptions.LoGuardAuthException;
import com.loguard.sdk.exceptions.LoGuardConnectionException;
import com.loguard.sdk.exceptions.LoGuardQuotaException;
import com.loguard.sdk.support.MockLoGuardServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransportIntegrationTest {

    private static MockLoGuardServer server;
    private static Transport transport;

    @BeforeAll
    static void start() throws IOException {
        server = new MockLoGuardServer();
        transport = new Transport(Duration.ofSeconds(2));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @SuppressWarnings("unchecked")
    @Test
    void successfulIngest() {
        Object result = transport.sendSync(server.baseUrl() + "/v1/ingest/ok", Map.of(),
            Map.of("events", List.of()), Duration.ofSeconds(2), 1, "POST", "test-key");
        assertTrue((Boolean) ((Map<String, Object>) result).get("ok"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void retriesOnServerErrorThenSucceeds() {
        Object result = transport.sendSync(server.baseUrl() + "/v1/ingest/fail-twice-then-ok", Map.of(),
            Map.of("events", List.of()), Duration.ofSeconds(2), 5, "POST", "test-key");
        assertTrue((Boolean) ((Map<String, Object>) result).get("ok"));
    }

    @Test
    void exhaustsRetriesAndThrowsConnectionError() {
        assertThrows(LoGuardConnectionException.class, () ->
            transport.sendSync(server.baseUrl() + "/v1/ingest/always-500", Map.of(),
                Map.of("events", List.of()), Duration.ofSeconds(1), 2, "POST", "test-key"));
    }

    @Test
    void unauthorizedMapsToAuthException() {
        assertThrows(LoGuardAuthException.class, () ->
            transport.sendSync(server.baseUrl() + "/v1/ingest/unauthorized", Map.of(),
                Map.of("events", List.of()), Duration.ofSeconds(2), 1, "POST", "test-key"));
    }

    @Test
    void quotaBodyMapsToQuotaException() {
        assertThrows(LoGuardQuotaException.class, () ->
            transport.sendSync(server.baseUrl() + "/v1/ingest/quota", Map.of(),
                Map.of("events", List.of()), Duration.ofSeconds(2), 1, "POST", "test-key"));
    }

    @Test
    void malformedJsonResponseDoesNotCrashTheClient() {
        Object result = transport.sendSync(server.baseUrl() + "/v1/ingest/malformed-json", Map.of(),
            Map.of("events", List.of()), Duration.ofSeconds(2), 1, "POST", "test-key");
        assertNull(result);
    }

    @Test
    void oversizedResponseIsAbortedNotBuffered() {
        assertThrows(LoGuardConnectionException.class, () ->
            transport.sendSync(server.baseUrl() + "/v1/ingest/oversized", Map.of(),
                Map.of("events", List.of()), Duration.ofSeconds(5), 1, "POST", "test-key"));
    }

    @Test
    void redirectIsNeverFollowed() {
        // A 3xx is not in the retry set and not a mapped error status,
        // so raiseForStatus() returns the (empty/null) decoded body --
        // the key property is that this never reached attacker.example.
        Object result = transport.sendSync(server.baseUrl() + "/v1/ingest/redirect", Map.of(),
            Map.of("events", List.of()), Duration.ofSeconds(2), 1, "POST", "test-key");
        assertNull(result);
    }
}
