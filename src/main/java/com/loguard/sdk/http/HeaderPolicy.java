package com.loguard.sdk.http;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared header-tracking policy for HTTP integrations.
 *
 * Mirrors the FastAPI/Laravel middleware policy in the sibling SDKs:
 * request headers are NEVER forwarded to LoGuard by default. An
 * application can opt in to sending specific header names for the
 * exploit-pattern detectors, but a fixed deny-list of headers that
 * can carry credentials is enforced in code -- not just documented --
 * so a misconfiguration can never leak secrets to LoGuard.
 */
public final class HeaderPolicy {

    /**
     * Header names that have historically carried real-world RCE
     * payloads. A starting point for trackHeaders, not a guarantee of
     * completeness.
     */
    public static final List<String> KNOWN_EXPLOIT_HEADERS = List.of(
        "user-agent", "referer", "x-beresource", "x-anonresource-backend"
    );

    /** Never forwarded, even if explicitly requested. */
    public static final Set<String> FORBIDDEN_HEADERS = Set.of(
        "authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token", "proxy-authorization"
    );

    public static final int MAX_HEADER_VALUE_LENGTH = 512;

    private HeaderPolicy() {
    }

    public static List<String> sanitizeRequested(List<String> requested) {
        Set<String> normalized = new LinkedHashSet<>();
        if (requested != null) {
            for (String h : requested) {
                if (h != null) {
                    normalized.add(h.toLowerCase());
                }
            }
        }
        normalized.removeAll(FORBIDDEN_HEADERS);
        return List.copyOf(normalized);
    }

    public static Map<String, String> collect(List<String> trackHeaders, Function<String, String> getHeader) {
        Map<String, String> collected = new LinkedHashMap<>();
        if (trackHeaders == null || trackHeaders.isEmpty()) {
            return collected;
        }
        for (String name : trackHeaders) {
            String value = getHeader.apply(name);
            if (value != null) {
                collected.put(name, value.length() > MAX_HEADER_VALUE_LENGTH
                    ? value.substring(0, MAX_HEADER_VALUE_LENGTH)
                    : value);
            }
        }
        return collected;
    }
}
