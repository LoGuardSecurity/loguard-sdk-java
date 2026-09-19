package com.loguard.sdk.internal;

import com.loguard.sdk.exceptions.LoGuardAuthException;
import com.loguard.sdk.exceptions.LoGuardConflictException;
import com.loguard.sdk.exceptions.LoGuardConnectionException;
import com.loguard.sdk.exceptions.LoGuardNotFoundException;
import com.loguard.sdk.exceptions.LoGuardQuotaException;
import com.loguard.sdk.exceptions.LoGuardValidationException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Signed HTTP transport for the LoGuard Java SDK.
 *
 * Built entirely on {@link java.net.http.HttpClient} (JDK 11+) -- no
 * HTTP client dependency needed. {@link HttpClient} instances are
 * thread-safe and keep an internal connection pool, and are meant to
 * be shared/reused, which is exactly what a single {@code
 * LoGuardClient} does (one Transport, one HttpClient, for the whole
 * client lifecycle).
 *
 * Security hardening (see docs/SECURITY.md):
 *  - TLS certificate + hostname verification uses the platform default
 *    {@code SSLContext} and is not configurable to "off" from
 *    application code.
 *  - HTTP redirects are never followed automatically
 *    ({@link HttpClient.Redirect#NEVER}) -- a 3xx is treated as a
 *    plain non-2xx result rather than silently re-sent (with the
 *    signed API key) to a second, potentially attacker-influenced URL.
 *  - Response bodies are capped (see MAX_RESPONSE_BYTES) via a custom
 *    {@link HttpResponse.BodySubscriber} so a malicious/misbehaving
 *    server can't exhaust client memory.
 */
public final class Transport {

    private static final Set<Integer> RETRY_STATUSES = Set.of(500, 502, 503, 504);
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024; // 5 MiB

    private final HttpClient httpClient;

    public Transport(Duration connectTimeout) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    /** Exposed for advanced use (e.g. observability hooks); optional. */
    public HttpClient httpClient() {
        return httpClient;
    }

    public Object sendSync(String url, Map<String, String> headers, Map<String, Object> payload,
                            Duration timeout, int retries, String method, String apiKey) {
        LoGuardConnectionException lastException = new LoGuardConnectionException("Unknown error");

        for (int attempt = 1; attempt <= Math.max(1, retries); attempt++) {
            try {
                byte[] body = Signing.buildBody(payload);
                HttpRequest request;
                if (apiKey != null && !apiKey.isEmpty() && "POST".equals(method)) {
                    Signing.Signed signed = Signing.sign(apiKey, body);
                    request = buildRequest(url, "POST", signed.headers, signed.body, timeout);
                } else {
                    request = buildRequest(url, method, headers, body, timeout);
                }

                Result result = execute(request);
                if (!RETRY_STATUSES.contains(result.status)) {
                    return raiseForStatus(result.status, result.body);
                }
                lastException = new LoGuardConnectionException("Server error " + result.status);
            } catch (TransportIoException e) {
                lastException = new LoGuardConnectionException("Connection error: " + e.getMessage(), e);
            }

            sleepBackoff(attempt, retries);
        }

        throw lastException;
    }

    public Object sendSyncNoBody(String url, Map<String, String> headers, Duration timeout, int retries, String method) {
        LoGuardConnectionException lastException = new LoGuardConnectionException("Unknown error");

        for (int attempt = 1; attempt <= Math.max(1, retries); attempt++) {
            try {
                HttpRequest request = buildRequest(url, method, headers, null, timeout);
                Result result = execute(request);
                if (!RETRY_STATUSES.contains(result.status)) {
                    return raiseForStatus(result.status, result.body);
                }
                lastException = new LoGuardConnectionException("Server error " + result.status);
            } catch (TransportIoException e) {
                lastException = new LoGuardConnectionException("Connection error: " + e.getMessage(), e);
            }

            sleepBackoff(attempt, retries);
        }

        throw lastException;
    }

    private static void sleepBackoff(int attempt, int retries) {
        if (attempt < retries) {
            try {
                Thread.sleep((long) (400 * attempt));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new LoGuardConnectionException("Interrupted during retry backoff", ie);
            }
        }
    }

    private HttpRequest buildRequest(String url, String method, Map<String, String> headers, byte[] body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout);

        for (Map.Entry<String, String> h : headers.entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }

        String m = method == null ? "GET" : method.toUpperCase();
        HttpRequest.BodyPublisher publisher = (body != null && !"GET".equals(m))
            ? HttpRequest.BodyPublishers.ofByteArray(body)
            : HttpRequest.BodyPublishers.noBody();

        builder.method(m, publisher);

        return builder.build();
    }

    private static final class Result {
        final int status;
        final String body;

        Result(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private Result execute(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, new BoundedBodyHandler(MAX_RESPONSE_BYTES));
            return new Result(response.statusCode(), response.body());
        } catch (HttpTimeoutException e) {
            throw new TransportIoException("request timed out: " + e.getMessage());
        } catch (ResponseTooLargeIOException e) {
            throw new TransportIoException("response exceeded " + MAX_RESPONSE_BYTES + " bytes; aborted");
        } catch (IOException e) {
            throw new TransportIoException(describeIoException(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportIoException("interrupted: " + e.getMessage());
        }
    }

    private static String describeIoException(IOException e) {
        String cls = e.getClass().getSimpleName();
        return cls + (e.getMessage() != null ? ": " + e.getMessage() : "");
    }

    @SuppressWarnings("unchecked")
    private static Object raiseForStatus(int status, String rawBody) {
        Object decoded;
        try {
            decoded = Json.parse(rawBody);
        } catch (Json.JsonException e) {
            decoded = null;
        }
        Map<String, Object> d = (decoded instanceof Map) ? (Map<String, Object>) decoded : Collections.emptyMap();
        String snippet = rawBody == null ? "" : rawBody.substring(0, Math.min(400, rawBody.length()));

        if (status == 401) {
            throw new LoGuardAuthException("Invalid API key");
        }
        if (status == 402) {
            throw new LoGuardAuthException("Subscription expired — renew at loguard.org");
        }
        if (status == 403) {
            Object detail = d.getOrDefault("detail", snippet);
            if ("subscription_expired".equals(detail)) {
                throw new LoGuardAuthException("Subscription expired — renew at loguard.org");
            }
            throw new LoGuardAuthException("Access denied: " + detail);
        }
        if (status == 404) {
            throw new LoGuardNotFoundException("Not found: " + d.getOrDefault("detail", snippet));
        }
        if (status == 409) {
            throw new LoGuardConflictException("Conflict: " + d.getOrDefault("detail", snippet));
        }
        if (status == 422) {
            throw new LoGuardValidationException("Validation error: " + snippet);
        }
        if (status == 429) {
            Object detailRaw = d.get("detail");
            if (detailRaw instanceof Map) {
                Map<String, Object> detail = (Map<String, Object>) detailRaw;
                if ("usage_limit_exceeded".equals(detail.get("err"))) {
                    throw new LoGuardQuotaException(String.format(
                        "Monthly quota exceeded: %s/%s events on plan '%s'. Upgrade at loguard.org",
                        detail.get("used"), detail.get("limit"), detail.get("plan")
                    ));
                }
            }
            throw new LoGuardConnectionException("Rate limited: " + snippet);
        }
        if (status >= 500) {
            throw new LoGuardConnectionException("Server error (" + status + "): " + snippet);
        }

        return decoded;
    }

    /** Internal marker for network/transport-level I/O failures. */
    static final class TransportIoException extends RuntimeException {
        TransportIoException(String message) {
            super(message);
        }
    }

    /** Thrown (wrapped as an IOException) when a response exceeds MAX_RESPONSE_BYTES. */
    private static final class ResponseTooLargeIOException extends IOException {
        ResponseTooLargeIOException(String message) {
            super(message);
        }
    }

    /**
     * A {@link HttpResponse.BodyHandler} that aborts the subscription
     * once more than {@code maxBytes} have been received, instead of
     * buffering an attacker-controlled amount of data into a String.
     */
    private static final class BoundedBodyHandler implements HttpResponse.BodyHandler<String> {
        private final int maxBytes;

        BoundedBodyHandler(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public HttpResponse.BodySubscriber<String> apply(HttpResponse.ResponseInfo responseInfo) {
            return new BoundedStringSubscriber(maxBytes);
        }
    }

    private static final class BoundedStringSubscriber implements HttpResponse.BodySubscriber<String> {
        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private volatile boolean done = false;

        BoundedStringSubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            if (done) {
                return;
            }
            int incoming = 0;
            for (ByteBuffer bb : item) {
                incoming += bb.remaining();
            }
            if (buffer.size() + incoming > maxBytes) {
                done = true;
                subscription.cancel();
                result.completeExceptionally(new ResponseTooLargeIOException("response exceeded " + maxBytes + " bytes"));
                return;
            }
            for (ByteBuffer bb : item) {
                byte[] chunk = new byte[bb.remaining()];
                bb.get(chunk);
                buffer.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (!done) {
                done = true;
                result.completeExceptionally(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                result.complete(buffer.toString(StandardCharsets.UTF_8));
            }
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }
    }
}
