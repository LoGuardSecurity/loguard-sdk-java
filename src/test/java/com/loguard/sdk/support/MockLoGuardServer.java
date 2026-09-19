package com.loguard.sdk.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback HTTP mock server for transport-level integration tests,
 * built entirely on {@code com.sun.net.httpserver.HttpServer} (JDK
 * built-in, zero extra test dependency needed -- same philosophy as
 * the PHP SDK's `php -S` based mock server).
 */
public final class MockLoGuardServer implements AutoCloseable {

    private final HttpServer server;
    private final ConcurrentHashMap<String, AtomicInteger> hitCounts = new ConcurrentHashMap<>();
    public final AtomicInteger totalRequests = new AtomicInteger(0);

    public MockLoGuardServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        totalRequests.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        // drain request body
        ex.getRequestBody().readAllBytes();

        switch (path) {
            case "/v1/ingest/ok":
                respond(ex, 200, "{\"ok\":true,\"accepted\":1,\"dropped\":0,\"alerts\":[],\"plan\":\"pro\",\"usage\":{}}");
                break;
            case "/v1/ingest/fail-twice-then-ok": {
                int n = hitCounts.computeIfAbsent(path, k -> new AtomicInteger(0)).incrementAndGet();
                if (n < 3) {
                    respond(ex, 503, "{\"detail\":\"temporarily unavailable\"}");
                } else {
                    respond(ex, 200, "{\"ok\":true,\"accepted\":1,\"dropped\":0,\"alerts\":[],\"plan\":\"pro\",\"usage\":{}}");
                }
                break;
            }
            case "/v1/ingest/always-500":
                respond(ex, 500, "{\"detail\":\"internal error\"}");
                break;
            case "/v1/ingest/unauthorized":
                respond(ex, 401, "{\"detail\":\"invalid api key\"}");
                break;
            case "/v1/ingest/quota":
                respond(ex, 429, "{\"detail\":{\"err\":\"usage_limit_exceeded\",\"used\":1000,\"limit\":1000,\"plan\":\"free\"}}");
                break;
            case "/v1/ingest/malformed-json":
                respond(ex, 200, "{not valid json!!");
                break;
            case "/v1/ingest/oversized": {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, 6L * 1024 * 1024);
                try (OutputStream os = ex.getResponseBody()) {
                    byte[] chunk = new byte[64 * 1024];
                    java.util.Arrays.fill(chunk, (byte) 'a');
                    for (int i = 0; i < (6 * 1024 * 1024) / chunk.length; i++) {
                        os.write(chunk);
                    }
                }
                break;
            }
            case "/v1/ingest/redirect":
                ex.getResponseHeaders().add("Location", "https://attacker.example/steal");
                respond(ex, 302, "");
                break;
            default:
                respond(ex, 404, "{\"detail\":\"unknown mock route\"}");
        }
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
