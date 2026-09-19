package com.loguard.sdk;

import com.loguard.sdk.exceptions.LoGuardAuthException;
import com.loguard.sdk.exceptions.LoGuardValidationException;

import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Immutable LoGuard client configuration. Build with {@link Builder}.
 *
 * Validation mirrors the other LoGuard SDKs' init/configure step:
 * {@code apiKey} is required, {@code baseUrl} must be {@code https://}
 * unless the caller explicitly opts into {@code allowInsecureTransport}
 * (fail closed by default — the API key travels on every request).
 */
public final class LoGuardConfig {

    public static final String DEFAULT_BASE_URL = "https://loguard.org";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_RETRIES = 3;

    private static final Logger LOG = Logger.getLogger("com.loguard.sdk");

    private final String apiKey;
    private final String baseUrl;
    private final String env;
    private final Duration timeout;
    private final int retries;
    private final String service;
    private final boolean allowInsecureTransport;
    private final int maxQueueSize;
    private final int maxBatchSize;
    private final Duration flushInterval;
    private final int ioThreads;

    private LoGuardConfig(Builder b) {
        if (b.apiKey == null || b.apiKey.trim().isEmpty()) {
            throw new LoGuardAuthException("apiKey is required");
        }

        String url = b.baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.toLowerCase().startsWith("https://")) {
            if (!b.allowInsecureTransport) {
                throw new LoGuardValidationException(
                    "baseUrl must use https:// -- your apiKey is sent on every request and must not " +
                    "travel over plaintext HTTP. If you really need HTTP (e.g. a local proxy on a " +
                    "trusted network during development), call Builder#allowInsecureTransport(true) explicitly."
                );
            }
            LOG.log(Level.WARNING,
                "LoGuard SDK initialized with allowInsecureTransport=true -- apiKey and request " +
                "signatures are being sent over plaintext. Use this ONLY for local development on a " +
                "trusted network, never against a real deployment.");
        }

        String svc = b.service;
        if (svc == null || svc.isEmpty()) {
            svc = firstNonEmpty(System.getenv("OTEL_SERVICE_NAME"), System.getenv("SERVICE_NAME"));
        }

        this.apiKey = b.apiKey.trim();
        this.baseUrl = url;
        this.env = (b.env == null || b.env.isEmpty()) ? "production" : b.env;
        this.timeout = b.timeout;
        this.retries = Math.max(1, b.retries);
        this.service = svc;
        this.allowInsecureTransport = b.allowInsecureTransport;
        this.maxQueueSize = b.maxQueueSize;
        this.maxBatchSize = b.maxBatchSize;
        this.flushInterval = b.flushInterval;
        this.ioThreads = Math.max(1, b.ioThreads);
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    public String apiKey() {
        return apiKey;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String env() {
        return env;
    }

    public Duration timeout() {
        return timeout;
    }

    public int retries() {
        return retries;
    }

    public String service() {
        return service;
    }

    public boolean allowInsecureTransport() {
        return allowInsecureTransport;
    }

    public int maxQueueSize() {
        return maxQueueSize;
    }

    public int maxBatchSize() {
        return maxBatchSize;
    }

    public Duration flushInterval() {
        return flushInterval;
    }

    public int ioThreads() {
        return ioThreads;
    }

    public String ingestUrl() {
        return baseUrl + "/v1/ingest";
    }

    public String alertRulesUrl(Long ruleId) {
        String base = baseUrl + "/v1/alert-rules";
        return ruleId != null ? base + "/" + ruleId : base;
    }

    public static Builder builder(String apiKey) {
        return new Builder(apiKey);
    }

    public static final class Builder {
        private final String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private String env = "production";
        private Duration timeout = DEFAULT_TIMEOUT;
        private int retries = DEFAULT_RETRIES;
        private String service;
        private boolean allowInsecureTransport = false;

        // Async pipeline tuning -- see LoGuardClient. Defaults match the
        // other SDKs' fire-and-forget worker (50 events/batch, 250ms
        // flush interval, 2000-event bounded queue).
        private int maxQueueSize = 2000;
        private int maxBatchSize = 50;
        private Duration flushInterval = Duration.ofMillis(250);
        private int ioThreads = 2;

        private Builder(String apiKey) {
            this.apiKey = apiKey;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder env(String env) {
            this.env = env;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder retries(int retries) {
            this.retries = retries;
            return this;
        }

        public Builder service(String service) {
            this.service = service;
            return this;
        }

        public Builder allowInsecureTransport(boolean allow) {
            this.allowInsecureTransport = allow;
            return this;
        }

        public Builder maxQueueSize(int maxQueueSize) {
            this.maxQueueSize = maxQueueSize;
            return this;
        }

        public Builder maxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        public Builder flushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
            return this;
        }

        /**
         * Number of threads backing the bounded async I/O executor used
         * by {@code eventAsync()}. Kept small and fixed on purpose — see
         * {@link LoGuardClient} for why this is a bounded pool, not
         * unbounded thread-per-event.
         */
        public Builder ioThreads(int ioThreads) {
            this.ioThreads = ioThreads;
            return this;
        }

        public LoGuardConfig build() {
            return new LoGuardConfig(this);
        }
    }
}
