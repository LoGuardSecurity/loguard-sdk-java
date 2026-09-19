package com.loguard.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AlertCondition {
    private final String field;
    private final String op;
    private final Object value;

    public AlertCondition(String field, String op, Object value) {
        this.field = field;
        this.op = op;
        this.value = value;
    }

    public String field() {
        return field;
    }

    public String op() {
        return op;
    }

    public Object value() {
        return value;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("op", op);
        m.put("value", value);
        return m;
    }

    @SuppressWarnings("unchecked")
    static AlertCondition fromMap(Object raw) {
        Map<String, Object> d = (Map<String, Object>) raw;
        return new AlertCondition(UsageInfo.asString(d.get("field")), UsageInfo.asString(d.get("op")), d.get("value"));
    }
}
