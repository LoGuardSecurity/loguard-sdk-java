package com.loguard.sdk;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelsTest {

    @Test
    void ingestResultFromWellFormedResponse() {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("kind", "brute_force");
        alert.put("severity", "high");
        alert.put("ip", "1.2.3.4");
        alert.put("path", "/login");
        alert.put("score", 87);
        alert.put("details", Map.of());

        Map<String, Object> usage = Map.of("used", 100, "limit", 1000, "month", "2026-09");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("accepted", 3);
        resp.put("dropped", 0);
        resp.put("alerts", List.of(alert));
        resp.put("plan", "pro");
        resp.put("usage", usage);

        IngestResult result = IngestResult.fromResponse(resp);
        assertTrue(result.ok());
        assertEquals(3, result.inserted());
        assertEquals(1, result.alertsFired());
        assertEquals("brute_force", result.alerts().get(0).kind());
        assertEquals("pro", result.plan());
        assertEquals(900, result.usageInfo().remaining());
        assertFalse(result.usageInfo().isNearLimit());
    }

    @Test
    void ingestResultToleratesMalformedOrEmptyResponse() {
        IngestResult result = IngestResult.fromResponse(null);
        assertFalse(result.ok());
        assertEquals(0, result.inserted());
        assertTrue(result.alerts().isEmpty());

        IngestResult result2 = IngestResult.fromResponse(Map.of("unexpected", "shape"));
        assertFalse(result2.ok());
    }

    @Test
    void usageNearLimit() {
        IngestResult result = IngestResult.fromResponse(Map.of("usage", Map.of("used", 850, "limit", 1000, "month", "2026-09")));
        assertTrue(result.usageInfo().isNearLimit());
    }

    @Test
    void unlimitedPlanHasNoNearLimitWarning() {
        IngestResult result = IngestResult.fromResponse(Map.of("usage", Map.of("used", 999999, "limit", 0, "month", "2026-09")));
        assertEquals(-1, result.usageInfo().remaining());
        assertFalse(result.usageInfo().isNearLimit());
    }

    @Test
    void eventMapShapeMatchesIngestContract() {
        Event event = new Event("login_failed", "1.2.3.4", "/login", 401, "2026-09-19T10:00:00Z", "user_1", "api", Map.of("env", "production"));
        Map<String, Object> map = event.toMap();
        assertEquals(List.of("type", "ip", "path", "status_code", "ts", "user_id", "service", "meta"), List.copyOf(map.keySet()));
        assertEquals(401, map.get("status_code"));
    }
}
