package com.loguard.sdk;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single security event as sent to {@code POST /v1/ingest}.
 *
 * Field shape and validation are identical to the other LoGuard SDKs
 * (see LoGuardClient#buildEvent) so the wire payload is
 * indistinguishable between languages.
 */
public final class Event {
    private final String type;
    private final String ip;
    private final String path;
    private final int statusCode;
    private final String ts;
    private final String userId;
    private final String service;
    private final Map<String, Object> meta;

    public Event(String type, String ip, String path, int statusCode, String ts,
                 String userId, String service, Map<String, Object> meta) {
        this.type = type;
        this.ip = ip;
        this.path = path;
        this.statusCode = statusCode;
        this.ts = ts != null ? ts : DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        this.userId = userId;
        this.service = service;
        this.meta = meta != null ? meta : new LinkedHashMap<>();
    }

    public String type() {
        return type;
    }

    public String ip() {
        return ip;
    }

    public String path() {
        return path;
    }

    public int statusCode() {
        return statusCode;
    }

    public String ts() {
        return ts;
    }

    public String userId() {
        return userId;
    }

    public String service() {
        return service;
    }

    public Map<String, Object> meta() {
        return meta;
    }

    /** Wire representation matching every other LoGuard SDK's Event JSON shape. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("ip", ip);
        m.put("path", path);
        m.put("status_code", statusCode);
        m.put("ts", ts);
        m.put("user_id", userId);
        m.put("service", service);
        m.put("meta", meta);
        return m;
    }
}
