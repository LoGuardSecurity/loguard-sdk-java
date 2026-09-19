package com.loguard.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** An alert fired by the server in response to an ingest request. */
public final class AlertOut {
    private final String kind;
    private final String severity;
    private final String ip;
    private final String path;
    private final int score;
    private final Map<String, Object> details;

    public AlertOut(String kind, String severity, String ip, String path, int score, Map<String, Object> details) {
        this.kind = kind;
        this.severity = severity;
        this.ip = ip;
        this.path = path;
        this.score = score;
        this.details = details != null ? details : new LinkedHashMap<>();
    }

    public String kind() {
        return kind;
    }

    public String severity() {
        return severity;
    }

    public String ip() {
        return ip;
    }

    public String path() {
        return path;
    }

    public int score() {
        return score;
    }

    public Map<String, Object> details() {
        return details;
    }

    @SuppressWarnings("unchecked")
    static AlertOut fromMap(Object raw) {
        Map<String, Object> d = (raw instanceof Map) ? (Map<String, Object>) raw : Collections.emptyMap();
        Object detailsRaw = d.get("details");
        Map<String, Object> details = (detailsRaw instanceof Map) ? (Map<String, Object>) detailsRaw : new LinkedHashMap<>();
        return new AlertOut(
            UsageInfo.asString(d.get("kind")),
            UsageInfo.asString(d.get("severity")),
            UsageInfo.asString(d.get("ip")),
            UsageInfo.asString(d.get("path")),
            (int) UsageInfo.asLong(d.get("score")),
            details
        );
    }

    @Override
    public String toString() {
        return "AlertOut(kind=" + kind + ", severity=" + severity + ", ip=" + ip + ")";
    }
}
