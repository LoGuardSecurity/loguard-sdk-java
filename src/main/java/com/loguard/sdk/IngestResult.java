package com.loguard.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class IngestResult {
    private final boolean ok;
    private final long inserted;
    private final long dropped;
    private final int alertsFired;
    private final List<AlertOut> alerts;
    private final String plan;
    private final Map<String, Object> usage;

    public IngestResult(boolean ok, long inserted, long dropped, int alertsFired,
                         List<AlertOut> alerts, String plan, Map<String, Object> usage) {
        this.ok = ok;
        this.inserted = inserted;
        this.dropped = dropped;
        this.alertsFired = alertsFired;
        this.alerts = alerts != null ? alerts : Collections.emptyList();
        this.plan = plan != null ? plan : "";
        this.usage = usage != null ? usage : Collections.emptyMap();
    }

    public boolean ok() {
        return ok;
    }

    public long inserted() {
        return inserted;
    }

    public long dropped() {
        return dropped;
    }

    public int alertsFired() {
        return alertsFired;
    }

    public List<AlertOut> alerts() {
        return alerts;
    }

    public String plan() {
        return plan;
    }

    public Map<String, Object> usage() {
        return usage;
    }

    public UsageInfo usageInfo() {
        return UsageInfo.fromMap(usage);
    }

    /**
     * Builds an IngestResult from a decoded JSON response, tolerating a
     * malformed/partial/absent body -- a bad server response must never
     * crash application code, only yield safe defaults.
     */
    @SuppressWarnings("unchecked")
    public static IngestResult fromResponse(Object raw) {
        Map<String, Object> d = (raw instanceof Map) ? (Map<String, Object>) raw : Collections.emptyMap();

        Object alertsRaw = d.get("alerts");
        List<Object> alertList = (alertsRaw instanceof List) ? (List<Object>) alertsRaw : Collections.emptyList();
        List<AlertOut> alerts = new ArrayList<>(alertList.size());
        for (Object a : alertList) {
            alerts.add(AlertOut.fromMap(a));
        }

        Object accepted = d.containsKey("accepted") ? d.get("accepted") : d.get("inserted");
        Object usageRaw = d.get("usage");
        Map<String, Object> usage = (usageRaw instanceof Map) ? (Map<String, Object>) usageRaw : Collections.emptyMap();

        return new IngestResult(
            Boolean.TRUE.equals(d.get("ok")),
            UsageInfo.asLong(accepted),
            UsageInfo.asLong(d.get("dropped")),
            alerts.size(),
            alerts,
            UsageInfo.asString(d.get("plan")),
            usage
        );
    }

    @Override
    public String toString() {
        return "IngestResult(ok=" + ok + ", inserted=" + inserted + ", alertsFired=" + alertsFired + ", plan=" + plan + ")";
    }
}
