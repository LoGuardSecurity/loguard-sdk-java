package com.loguard.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UsageInfo {
    private final long used;
    private final long limit;
    private final String month;

    public UsageInfo(long used, long limit, String month) {
        this.used = used;
        this.limit = limit;
        this.month = month;
    }

    public long used() {
        return used;
    }

    public long limit() {
        return limit;
    }

    public String month() {
        return month;
    }

    public long remaining() {
        if (limit <= 0) {
            return -1;
        }
        return Math.max(0, limit - used);
    }

    public boolean isNearLimit() {
        if (limit <= 0) {
            return false;
        }
        return ((double) used / (double) limit) > 0.8;
    }

    @SuppressWarnings("unchecked")
    static UsageInfo fromMap(Object raw) {
        Map<String, Object> d = (raw instanceof Map) ? (Map<String, Object>) raw : Collections.emptyMap();
        return new UsageInfo(asLong(d.get("used")), asLong(d.get("limit")), asString(d.get("month")));
    }

    static long asLong(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        return 0L;
    }

    static String asString(Object o) {
        return o != null ? String.valueOf(o) : "";
    }
}
