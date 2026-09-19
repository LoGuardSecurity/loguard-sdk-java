package com.loguard.sdk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AlertRule {
    private Long id;
    private String name;
    private List<AlertCondition> conditions;
    private String severity = "medium";
    private List<String> actions = new ArrayList<>(Collections.singletonList("notify"));
    private boolean enabled = true;
    private String description = "";
    private String logic = "and";
    private int cooldownSec = 60;
    private String createdAt;
    private String updatedAt;

    public AlertRule(String name, List<AlertCondition> conditions) {
        this.name = name;
        this.conditions = conditions;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public AlertRule name(String name) {
        this.name = name;
        return this;
    }

    public List<AlertCondition> conditions() {
        return conditions;
    }

    public AlertRule conditions(List<AlertCondition> conditions) {
        this.conditions = conditions;
        return this;
    }

    public String severity() {
        return severity;
    }

    public AlertRule severity(String severity) {
        this.severity = severity;
        return this;
    }

    public List<String> actions() {
        return actions;
    }

    public AlertRule actions(List<String> actions) {
        this.actions = actions;
        return this;
    }

    public boolean enabled() {
        return enabled;
    }

    public AlertRule enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public String description() {
        return description;
    }

    public AlertRule description(String description) {
        this.description = description;
        return this;
    }

    public String logic() {
        return logic;
    }

    public AlertRule logic(String logic) {
        this.logic = logic;
        return this;
    }

    public int cooldownSec() {
        return cooldownSec;
    }

    public AlertRule cooldownSec(int cooldownSec) {
        this.cooldownSec = cooldownSec;
        return this;
    }

    public String createdAt() {
        return createdAt;
    }

    public String updatedAt() {
        return updatedAt;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        List<Object> conds = new ArrayList<>();
        for (AlertCondition c : conditions) {
            conds.add(c.toMap());
        }
        m.put("conditions", conds);
        m.put("severity", severity);
        m.put("actions", actions);
        m.put("enabled", enabled);
        m.put("description", description);
        m.put("logic", logic);
        m.put("cooldown_sec", cooldownSec);
        return m;
    }

    @SuppressWarnings("unchecked")
    static AlertRule fromMap(Object raw) {
        Map<String, Object> d = (raw instanceof Map) ? (Map<String, Object>) raw : Collections.emptyMap();

        List<AlertCondition> conditions = new ArrayList<>();
        Object condsRaw = d.get("conditions");
        if (condsRaw instanceof List) {
            for (Object c : (List<Object>) condsRaw) {
                conditions.add(AlertCondition.fromMap(c));
            }
        }

        AlertRule rule = new AlertRule(UsageInfo.asString(d.get("name")), conditions);
        Object idRaw = d.get("id");
        rule.id = (idRaw instanceof Number) ? ((Number) idRaw).longValue() : null;
        rule.severity = d.containsKey("severity") ? UsageInfo.asString(d.get("severity")) : "medium";

        List<String> actions = new ArrayList<>();
        Object actionsRaw = d.get("actions");
        if (actionsRaw instanceof List) {
            for (Object a : (List<Object>) actionsRaw) {
                actions.add(String.valueOf(a));
            }
        } else {
            actions.add("notify");
        }
        rule.actions = actions;

        rule.enabled = d.containsKey("enabled") ? Boolean.TRUE.equals(d.get("enabled")) : true;
        rule.description = d.containsKey("description") ? UsageInfo.asString(d.get("description")) : "";
        rule.logic = d.containsKey("logic") ? UsageInfo.asString(d.get("logic")) : "and";
        rule.cooldownSec = d.containsKey("cooldown_sec") ? (int) UsageInfo.asLong(d.get("cooldown_sec")) : 60;
        rule.createdAt = d.get("created_at") != null ? String.valueOf(d.get("created_at")) : null;
        rule.updatedAt = d.get("updated_at") != null ? String.valueOf(d.get("updated_at")) : null;

        return rule;
    }
}
