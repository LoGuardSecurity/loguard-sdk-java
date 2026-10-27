package com.loguard.sdk.internal;

import com.loguard.sdk.exceptions.LoGuardException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AiErrorPrivacyTest {
    @Test
    void errorResponsesMustNotExposeInputContent() throws Exception {
        for (int status : new int[] {403, 404, 409, 422, 429, 500}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/ingest", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = ("{\"detail\":{\"err\":\"usage_limit_exceeded\","
                    + "\"plan\":\"SYNTHETIC_PRIVATE_MARKER\",\"input\":\"SYNTHETIC_PRIVATE_MARKER\"}}")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(body); }
                exchange.close();
            });
            server.start();
            try {
                Transport transport = new Transport(Duration.ofSeconds(2));
                String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/ingest";
                LoGuardException error = assertThrows(LoGuardException.class, () -> transport.sendSync(
                    url, Map.of(), Map.of("events", List.of(Map.of("type", "ai_llm_request",
                    "meta", Map.of("prompt", "synthetic")))), Duration.ofSeconds(2), 1, "POST", "synthetic-key"));
                assertFalse(error.getMessage().contains("SYNTHETIC_PRIVATE_MARKER"));
            } finally { server.stop(0); }
        }
    }
}
