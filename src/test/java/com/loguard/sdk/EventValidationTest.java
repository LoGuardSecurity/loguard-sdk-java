package com.loguard.sdk;

import com.loguard.sdk.exceptions.LoGuardValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation happens before any network I/O, so these run fully
 * offline even though LoGuardClient normally talks to the LoGuard API.
 */
class EventValidationTest {

    private LoGuardClient client() {
        // Unreachable base_url is fine -- validation errors are raised
        // before Transport is ever invoked.
        return new LoGuardClient(LoGuardConfig.builder("lg_test_key").baseUrl("https://127.0.0.1:1").timeout(java.time.Duration.ofMillis(200)).build());
    }

    private Event buildEvent(LoGuardClient client, String type, String ip, String path, int status,
                              String userId, String service, Map<String, Object> meta, Instant ts) throws Exception {
        Method m = LoGuardClient.class.getDeclaredMethod("buildEvent",
            String.class, String.class, String.class, int.class, String.class, String.class, Map.class, Instant.class);
        m.setAccessible(true);
        return (Event) m.invoke(client, type, ip, path, status, userId, service, meta, ts);
    }

    @Test
    void rejectsEmptyType() {
        LoGuardClient c = client();
        assertThrows(LoGuardValidationException.class, () -> c.event("", "1.2.3.4", "/login", 401));
    }

    @Test
    void rejectsEmptyIp() {
        LoGuardClient c = client();
        assertThrows(LoGuardValidationException.class, () -> c.event("login_failed", "", "/login", 401));
    }

    @Test
    void rejectsEmptyPath() {
        LoGuardClient c = client();
        assertThrows(LoGuardValidationException.class, () -> c.event("login_failed", "1.2.3.4", "", 401));
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 600, -1, 0, 10000})
    void rejectsOutOfRangeStatusCode(int status) {
        LoGuardClient c = client();
        assertThrows(LoGuardValidationException.class, () -> c.event("login_failed", "1.2.3.4", "/login", status));
    }

    @Test
    void pathGetsLeadingSlashAdded() throws Exception {
        Event e = buildEvent(client(), "http_request", "1.2.3.4", "no-leading-slash", 200, null, null, null, null);
        assertEquals("/no-leading-slash", e.path());
    }

    @Test
    void pathIsTruncatedTo1024Chars() throws Exception {
        String longPath = "/" + "a".repeat(2000);
        Event e = buildEvent(client(), "http_request", "1.2.3.4", longPath, 200, null, null, null, null);
        assertEquals(1024, e.path().length());
    }

    @Test
    void typeIsLowercasedAndTruncated() throws Exception {
        String longType = "AB".repeat(100);
        Event e = buildEvent(client(), longType, "1.2.3.4", "/x", 200, null, null, null, null);
        assertEquals(64, e.type().length());
        assertEquals(e.type().toLowerCase(), e.type());
    }

    @Test
    void metaAlwaysCarriesEnv() throws Exception {
        Event e = buildEvent(client(), "http_request", "1.2.3.4", "/x", 200, null, null, Map.of("custom", "value"), null);
        assertEquals("production", e.meta().get("env"));
        assertEquals("value", e.meta().get("custom"));
    }
}
