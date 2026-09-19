package com.loguard.sdk;

import com.loguard.sdk.exceptions.LoGuardValidationException;
import com.loguard.sdk.internal.Transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Manage user-defined alert rules. Access via {@link LoGuardClient#alerts()}.
 *
 * Note on signing (matches every other SDK): only POST requests are
 * HMAC-signed by the transport layer. GET/PUT/DELETE requests here
 * are sent with the plain X-Api-Key header, matching the ingest
 * backend's current contract.
 */
public final class AlertsClient {

    private final LoGuardClient client;

    AlertsClient(LoGuardClient client) {
        this.client = client;
    }

    public AlertRule create(AlertRule rule) {
        LoGuardConfig c = client.config();
        Object data = client.transport().sendSync(
            c.alertRulesUrl(null), client.defaultHeaders(), rule.toMap(),
            c.timeout(), c.retries(), "POST", c.apiKey()
        );
        return AlertRule.fromMap(data);
    }

    @SuppressWarnings("unchecked")
    public List<AlertRule> list() {
        LoGuardConfig c = client.config();
        Object data = client.transport().sendSyncNoBody(
            c.alertRulesUrl(null), client.defaultHeaders(), c.timeout(), c.retries(), "GET"
        );
        List<Object> items;
        if (data instanceof List) {
            items = (List<Object>) data;
        } else if (data instanceof Map && ((Map<String, Object>) data).get("rules") instanceof List) {
            items = (List<Object>) ((Map<String, Object>) data).get("rules");
        } else {
            items = Collections.emptyList();
        }
        List<AlertRule> rules = new ArrayList<>(items.size());
        for (Object item : items) {
            rules.add(AlertRule.fromMap(item));
        }
        return rules;
    }

    public AlertRule get(long ruleId) {
        LoGuardConfig c = client.config();
        Object data = client.transport().sendSyncNoBody(
            c.alertRulesUrl(ruleId), client.defaultHeaders(), c.timeout(), c.retries(), "GET"
        );
        return AlertRule.fromMap(data);
    }

    public AlertRule update(AlertRule rule) {
        if (rule.id() == null) {
            throw new LoGuardValidationException("rule.id is required to update");
        }
        LoGuardConfig c = client.config();
        Object data = client.transport().sendSync(
            c.alertRulesUrl(rule.id()), client.defaultHeaders(), rule.toMap(),
            c.timeout(), c.retries(), "PUT", c.apiKey()
        );
        return AlertRule.fromMap(data);
    }

    public void delete(long ruleId) {
        LoGuardConfig c = client.config();
        client.transport().sendSyncNoBody(
            c.alertRulesUrl(ruleId), client.defaultHeaders(), c.timeout(), c.retries(), "DELETE"
        );
    }

    public AlertRule enable(long ruleId) {
        AlertRule rule = get(ruleId);
        rule.enabled(true);
        return update(rule);
    }

    public AlertRule disable(long ruleId) {
        AlertRule rule = get(ruleId);
        rule.enabled(false);
        return update(rule);
    }
}
