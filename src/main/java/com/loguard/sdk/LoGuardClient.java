package com.loguard.sdk;

import com.loguard.sdk.exceptions.LoGuardException;
import com.loguard.sdk.exceptions.LoGuardValidationException;
import com.loguard.sdk.internal.Signing;
import com.loguard.sdk.internal.Transport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Core LoGuard client.
 *
 * Framework-agnostic: has no knowledge of Servlet, Spring, or any
 * other web framework. {@code com.loguard.sdk.integration.servlet}
 * is a thin, optional adapter built entirely on top of this public
 * API — the core module does not require {@code jakarta.servlet-api}
 * at runtime.
 *
 * <h2>Thread safety</h2>
 * A single {@code LoGuardClient} instance is safe to share across
 * threads and is meant to be constructed once per application/service
 * (a singleton, or a Spring {@code @Bean}), not per-request:
 * <ul>
 *   <li>{@link Transport} wraps one {@link java.net.http.HttpClient},
 *       which is thread-safe and pools connections internally.</li>
 *   <li>The {@code eventAsync()} queue is a bounded
 *       {@link ArrayBlockingQueue} (capacity {@link LoGuardConfig#maxQueueSize()}) --
 *       concurrent producers never grow it past that bound; once full,
 *       the newest event is dropped rather than blocking the caller
 *       or growing memory unboundedly.</li>
 *   <li>Exactly one background flush thread is created per client
 *       (not one thread per event, not one per request) — see
 *       {@link #startWorkerIfNeeded()}.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * Call {@link #shutdown()} (or use try-with-resources — this class
 * implements {@link AutoCloseable}) when your application shuts down,
 * so the flush worker thread stops cleanly and any still-queued
 * events get a bounded final flush attempt instead of being silently
 * dropped.
 */
public final class LoGuardClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("com.loguard.sdk");

    private final LoGuardConfig config;
    private final Transport transport;
    private final AlertsClient alerts;

    private final BlockingQueue<Event> queue;
    private final AtomicBoolean workerStarted = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private volatile Thread workerThread;
    private final CountDownLatch workerStopped = new CountDownLatch(1);

    private volatile Consumer<Event> onDropped;

    public LoGuardClient(LoGuardConfig config) {
        this.config = config;
        this.transport = new Transport(config.timeout());
        this.alerts = new AlertsClient(this);
        this.queue = new ArrayBlockingQueue<>(config.maxQueueSize());
    }

    public LoGuardConfig config() {
        return config;
    }

    public AlertsClient alerts() {
        return alerts;
    }

    Transport transport() {
        return transport;
    }

    /** Invoked (on the flush worker thread) whenever a queued event is dropped because the buffer is full. */
    public void onDropped(Consumer<Event> callback) {
        this.onDropped = callback;
    }

    public IngestResult event(String type, String ip, String path, int statusCode) {
        return event(type, ip, path, statusCode, null, null, null, null);
    }

    public IngestResult event(String type, String ip, String path, int statusCode,
                               String userId, String service, Map<String, Object> meta, Instant ts) {
        Event event = buildEvent(type, ip, path, statusCode, userId, service, meta, ts);
        return sendEventsSync(List.of(event));
    }

    /**
     * @param events Each map uses the same keys as event()'s parameters:
     *               type, ip, path, status_code, user_id, service, meta, ts (ISO-8601 string or Instant).
     */
    public IngestResult eventBatch(List<Map<String, Object>> events) {
        List<Event> built = new ArrayList<>(events.size());
        for (Map<String, Object> e : events) {
            built.add(buildEventFromMap(e));
        }
        return sendEventsSync(built);
    }

    /**
     * Queue an event for background delivery without blocking the
     * caller. Never throws. Backed by one bounded queue and one
     * dedicated background thread per client (see class docs) — not
     * a new thread per call.
     */
    public void eventAsync(String type, String ip, String path, int statusCode,
                            String userId, String service, Map<String, Object> meta, Instant ts) {
        Event event;
        try {
            event = buildEvent(type, ip, path, statusCode, userId, service, meta, ts);
        } catch (LoGuardException e) {
            return;
        }

        startWorkerIfNeeded();

        if (!queue.offer(event)) {
            Consumer<Event> cb = onDropped;
            if (cb != null) {
                try {
                    cb.accept(event);
                } catch (RuntimeException ignored) {
                    // A misbehaving callback must never break event submission.
                }
            }
        }
    }

    public void eventAsync(String type, String ip, String path, int statusCode) {
        eventAsync(type, ip, path, statusCode, null, null, null, null);
    }

    /**
     * Synchronously drains and sends everything currently queued, in
     * batches of at most {@link LoGuardConfig#maxBatchSize()}. Best
     * effort — failures are swallowed (matching every other LoGuard
     * SDK's fire-and-forget semantics); flush() itself never throws.
     */
    public void flush() {
        List<Event> batch = new ArrayList<>(config.maxBatchSize());
        while (true) {
            batch.clear();
            queue.drainTo(batch, config.maxBatchSize());
            if (batch.isEmpty()) {
                return;
            }
            try {
                sendEventsSync(new ArrayList<>(batch));
            } catch (LoGuardException e) {
                LOG.log(Level.FINE, "LoGuard flush: dropping batch after delivery failure", e);
            }
        }
    }

    /**
     * Stops the background worker and performs a final bounded flush.
     * Safe to call multiple times; safe to call from a shutdown hook.
     */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        Thread t = workerThread;
        if (t != null) {
            t.interrupt();
            try {
                workerStopped.await(config.timeout().toMillis() + 1000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flush();
    }

    @Override
    public void close() {
        shutdown();
    }

    private void startWorkerIfNeeded() {
        if (shuttingDown.get()) {
            return;
        }
        if (workerStarted.compareAndSet(false, true)) {
            Thread t = new Thread(this::workerLoop, "loguard-sdk-flush-worker");
            t.setDaemon(true); // never keeps the JVM alive on its own
            this.workerThread = t;
            t.start();
        }
    }

    private void workerLoop() {
        try {
            List<Event> pending = new ArrayList<>(config.maxBatchSize());
            long flushIntervalMs = Math.max(1, config.flushInterval().toMillis());

            while (!shuttingDown.get() && !Thread.currentThread().isInterrupted()) {
                Event head;
                try {
                    head = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (head != null) {
                    pending.add(head);
                    queue.drainTo(pending, config.maxBatchSize() - pending.size());
                }
                if (!pending.isEmpty()) {
                    try {
                        sendEventsSync(new ArrayList<>(pending));
                    } catch (LoGuardException e) {
                        LOG.log(Level.FINE, "LoGuard background flush failed", e);
                    }
                    pending.clear();
                }
            }
        } finally {
            workerStopped.countDown();
        }
    }

    @SuppressWarnings("unchecked")
    private Event buildEventFromMap(Map<String, Object> e) {
        Object tsRaw = e.get("ts");
        Instant ts = null;
        if (tsRaw instanceof Instant) {
            ts = (Instant) tsRaw;
        } else if (tsRaw instanceof String && !((String) tsRaw).isEmpty()) {
            ts = Instant.parse((String) tsRaw);
        }
        return buildEvent(
            String.valueOf(e.getOrDefault("type", "")),
            String.valueOf(e.getOrDefault("ip", "")),
            String.valueOf(e.getOrDefault("path", "")),
            asInt(e.get("status_code")),
            e.get("user_id") != null ? String.valueOf(e.get("user_id")) : null,
            e.get("service") != null ? String.valueOf(e.get("service")) : null,
            (Map<String, Object>) e.getOrDefault("meta", null),
            ts
        );
    }

    private static int asInt(Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o instanceof String) {
            try {
                return Integer.parseInt((String) o);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Validation is intentionally identical to the other SDKs: required
     * fields, status_code range, path normalization/truncation,
     * lowercasing + length caps on type/ip/service.
     */
    private Event buildEvent(String type, String ip, String path, int statusCode,
                              String userId, String service, Map<String, Object> meta, Instant ts) {
        if (type == null || type.trim().isEmpty()) {
            throw new LoGuardValidationException("event type is required");
        }
        if (ip == null || ip.trim().isEmpty()) {
            throw new LoGuardValidationException("event ip is required");
        }
        if (path == null || path.trim().isEmpty()) {
            throw new LoGuardValidationException("event path is required");
        }
        if (statusCode < 100 || statusCode > 599) {
            throw new LoGuardValidationException("status_code must be in range 100..599");
        }

        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1024) {
            p = p.substring(0, 1024);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        if (meta != null) {
            m.putAll(meta);
        }
        m.put("env", config.env());

        String svc = (service != null && !service.isEmpty()) ? service : config.service();

        String tsString = (ts != null ? ts : Instant.now()).toString();

        return new Event(
            truncate(type.trim().toLowerCase(), 64),
            truncate(ip.trim(), 64),
            p,
            statusCode,
            tsString,
            userId != null ? truncate(userId, 128) : null,
            svc != null ? truncate(svc.trim().toLowerCase(), 64) : null,
            m
        );
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private IngestResult sendEventsSync(List<Event> events) {
        List<Object> eventMaps = new ArrayList<>(events.size());
        for (Event e : events) {
            eventMaps.add(e.toMap());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("events", eventMaps);

        Object data = transport.sendSync(
            config.ingestUrl(),
            defaultHeaders(),
            payload,
            config.timeout(),
            config.retries(),
            "POST",
            config.apiKey()
        );

        return IngestResult.fromResponse(data);
    }

    Map<String, String> defaultHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Api-Key", config.apiKey());
        h.put("Content-Type", "application/json");
        h.put("User-Agent", Signing.SDK_NAME + "/" + Signing.SDK_VERSION);
        return h;
    }
}
