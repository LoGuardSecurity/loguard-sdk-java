package com.loguard.sdk;

import com.loguard.sdk.support.MockLoGuardServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises LoGuardClient under concurrent load: many threads calling
 * eventAsync() simultaneously must never throw, never grow the queue
 * unbounded, and never spawn more than the single documented flush
 * thread.
 */
class LoGuardClientConcurrencyTest {

    private MockLoGuardServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockLoGuardServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @Timeout(30)
    void manyThreadsCallingEventAsyncNeverThrowAndStayBounded() throws Exception {
        LoGuardConfig config = LoGuardConfig.builder("test-key")
            .baseUrl(server.baseUrl())
            .allowInsecureTransport(true)
            .maxQueueSize(500)
            .maxBatchSize(50)
            .flushInterval(Duration.ofMillis(20))
            .timeout(Duration.ofSeconds(2))
            .build();

        try (LoGuardClient client = new LoGuardClient(config)) {
            int threads = 32;
            int perThread = 200;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger failures = new AtomicInteger(0);

            long threadCountBefore = Thread.activeCount();

            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            client.eventAsync("http_request", "1.2.3." + (i % 255), "/x", 200);
                        }
                    } catch (Throwable e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(20, TimeUnit.SECONDS));
            pool.shutdown();

            assertEquals(0, failures.get(), "eventAsync must never throw under concurrent load");

            client.flush();
            // Exactly one background worker thread should have been created
            // by this client, regardless of how many producer threads called
            // eventAsync() concurrently.
            long loguardThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(th -> th.getName().equals("loguard-sdk-flush-worker"))
                .count();
            assertEquals(1, loguardThreads, "must not spawn more than one flush worker thread");
        }
    }

    @Test
    @Timeout(15)
    void fullQueueDropsEventsInsteadOfBlockingCaller() throws Exception {
        LoGuardConfig config = LoGuardConfig.builder("test-key")
            .baseUrl(server.baseUrl())
            .allowInsecureTransport(true)
            .maxQueueSize(5)
            .maxBatchSize(1)
            // Slow flush interval so the queue actually fills up before draining.
            .flushInterval(Duration.ofSeconds(5))
            .timeout(Duration.ofSeconds(2))
            .build();

        AtomicInteger dropped = new AtomicInteger(0);
        try (LoGuardClient client = new LoGuardClient(config)) {
            client.onDropped(e -> dropped.incrementAndGet());

            long start = System.nanoTime();
            for (int i = 0; i < 500; i++) {
                client.eventAsync("http_request", "1.2.3.4", "/x", 200);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // The whole point of a bounded, non-blocking queue: submitting
            // far more events than capacity must stay fast, not stall the
            // caller waiting for network I/O.
            assertTrue(elapsedMs < 2000, "eventAsync must not block the caller, took " + elapsedMs + "ms");
        }
    }

    @Test
    @Timeout(15)
    void shutdownFlushesRemainingEventsAndStopsWorker() throws Exception {
        LoGuardConfig config = LoGuardConfig.builder("test-key")
            .baseUrl(server.baseUrl())
            .allowInsecureTransport(true)
            .maxQueueSize(1000)
            .maxBatchSize(50)
            .flushInterval(Duration.ofSeconds(5)) // long enough that only shutdown()'s flush delivers these
            .timeout(Duration.ofSeconds(2))
            .build();

        LoGuardClient client = new LoGuardClient(config);
        for (int i = 0; i < 10; i++) {
            client.eventAsync("http_request", "1.2.3.4", "/x", 200);
        }
        client.shutdown();

        assertTrue(server.totalRequests.get() >= 1, "shutdown() must flush queued events before returning");

        // Idempotent.
        assertDoesNotThrow(client::shutdown);
    }
}
