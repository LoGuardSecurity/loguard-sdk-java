# LoGuard Java SDK

Official Java SDK for LoGuard security monitoring and threat
detection. Framework-agnostic core (Java 11+, `java.net.http.HttpClient`)
with an optional Servlet-based web integration that works unmodified
under Tomcat, Jetty, Undertow, and — since Spring MVC/Boot's embedded
containers are themselves Servlet-based — Spring, without any
Spring-specific dependency.

Behaviorally consistent with the existing LoGuard SDKs (Python,
Node.js, Go, C#, PHP): identical wire protocol, validation rules,
error taxonomy, and retry/backoff behavior.

## Installation

Maven:

```xml
<dependency>
    <groupId>org.loguard</groupId>
    <artifactId>loguard-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

Requires Java 11+. Zero required runtime dependencies — see
[Dependencies](#dependencies) below.

## Quick start

```java
import com.loguard.sdk.LoGuardClient;
import com.loguard.sdk.LoGuardConfig;
import com.loguard.sdk.IngestResult;

LoGuardConfig config = LoGuardConfig.builder(System.getenv("LOGUARD_API_KEY"))
    .baseUrl(System.getenv().getOrDefault("LOGUARD_BASE_URL", "https://loguard.org"))
    .env(System.getenv().getOrDefault("LOGUARD_ENV", "production"))
    .build();

// Construct ONE client per application/service and share it — it's
// thread-safe and owns a pooled HttpClient plus a single background
// worker thread. See "Lifecycle" below.
LoGuardClient loguard = new LoGuardClient(config);

// Blocking — waits for the LoGuard API response.
IngestResult result = loguard.event(
    "login_failed", "1.2.3.4", "/api/login", 401,
    "user_123", null, Map.of("method", "POST"), null
);
System.out.println(result.inserted() + " accepted, " + result.alertsFired() + " alerts fired");

// Non-blocking — buffered on a bounded queue, delivered by a single
// background worker thread. Never throws.
loguard.eventAsync("http_request", "1.2.3.4", "/api/users", 200);

// Multiple events in one request.
IngestResult batchResult = loguard.eventBatch(List.of(
    Map.of("type", "http_request", "ip", "1.2.3.4", "path", "/", "status_code", 200),
    Map.of("type", "login_failed", "ip", "1.2.3.4", "path", "/login", "status_code", 401),
    Map.of("type", "http_request", "ip", "5.6.7.8", "path", "/.env", "status_code", 404)
));

// On application shutdown:
loguard.shutdown(); // or: try-with-resources, since LoGuardClient implements AutoCloseable
```

### Event types

| Type             | Description               |
|------------------|----------------------------|
| `http_request`   | Incoming HTTP request      |
| `login_failed`   | Failed authentication      |
| `login_success`  | Successful login           |
| `forbidden`      | Access denied              |
| `waf_block`      | Request blocked by a WAF   |
| `bot_detected`   | Bot traffic detected       |

## Lifecycle

`LoGuardClient` is meant to be constructed **once** per application
(a singleton, a Spring `@Bean`, a static field in a plain servlet app)
and shared across all threads/requests — not created per request. It
owns:

- One `java.net.http.HttpClient` (thread-safe, pools connections internally).
- One bounded `ArrayBlockingQueue` for `eventAsync()`.
- One dedicated background flush thread, started lazily on first
  `eventAsync()` call, not one thread per event or per request.

Call `shutdown()` (or use try-with-resources) when your application
stops, so the worker thread exits cleanly and queued events get a
final flush attempt.

### Spring Boot example

```java
@Configuration
public class LoGuardConfiguration {

    @Bean(destroyMethod = "shutdown")
    public LoGuardClient loGuardClient(@Value("${loguard.api-key}") String apiKey) {
        return new LoGuardClient(LoGuardConfig.builder(apiKey).build());
    }

    @Bean
    public FilterRegistrationBean<LoGuardServletFilter> loGuardFilter(LoGuardClient client) {
        FilterRegistrationBean<LoGuardServletFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new LoGuardServletFilter(client));
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.LOWEST_PRECEDENCE);
        return reg;
    }
}
```

### Plain Servlet container

Register `com.loguard.sdk.integration.servlet.LoGuardServletFilter` in
`web.xml` (or programmatically via `ServletContext.addFilter`) like
any other `Filter`. It depends only on `jakarta.servlet-api`
(`provided` scope — supplied by your container, not bundled by the SDK).

### Forwarding request headers (optional, off by default)

```java
new LoGuardServletFilter(
    client,
    Set.of(400, 401, 403, 404, 429, 500, 502, 503),
    HeaderPolicy.KNOWN_EXPLOIT_HEADERS, // or your own list
    List.of("10.0.0.0/8"),              // trusted proxies for X-Forwarded-For
    request -> request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : null
);
```

`Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token`,
and `Proxy-Authorization` are **never** forwarded, even if listed —
enforced in `HeaderPolicy.FORBIDDEN_HEADERS`, not just documented.

## Error handling

All SDK errors extend `com.loguard.sdk.exceptions.LoGuardException`
(unchecked — `RuntimeException`):

```java
import com.loguard.sdk.exceptions.*;

try {
    loguard.event("login_failed", ip, path, 401, null, null, null, null);
} catch (LoGuardQuotaException e) {
    // monthly quota exceeded
} catch (LoGuardConnectionException e) {
    // LoGuard unreachable / retries exhausted — not your app's fault
} catch (LoGuardException e) {
    // catch-all for anything else SDK-related
}
```

`eventAsync()` never throws — failures are logged at `FINE` and
dropped, matching every other LoGuard SDK's fire-and-forget semantics.

## Retry behavior

Requests retry on `500/502/503/504` and connection-level failures
(DNS, TLS, timeout, reset), up to `retries` attempts (default 3) with
linear backoff (`400ms * attempt`). `429` maps to
`LoGuardQuotaException` (quota) or is treated as a non-retried
rate-limit error, matching the other SDKs.

## Performance & concurrency

- Zero required runtime dependencies: `java.net.http.HttpClient` for
  transport (built-in connection pooling), a small dependency-free
  JSON codec for the handful of known wire shapes.
- `event()`/`eventBatch()` are the only blocking calls;
  `eventAsync()` never blocks the caller and never grows memory
  unboundedly — once the bounded queue (`maxQueueSize`, default 2000)
  is full, the newest event is dropped (optionally observable via
  `onDropped(...)`).
- Exactly one background thread per client, not one per event/request
  — see `LoGuardClientConcurrencyTest#manyThreadsCallingEventAsyncNeverThrowAndStayBounded`.
- Response bodies are capped at 5 MiB via a custom `BodySubscriber`
  that aborts the transfer, not buffered unbounded.
- `graceful shutdown`: `shutdown()`/`close()` stop the worker thread
  and attempt a final flush of queued events.

## Dependencies

| Dependency | Scope | Why |
|---|---|---|
| `jakarta.servlet-api` | `provided` | Only needed to compile `integration.servlet`; supplied by whatever container the app already runs on. Not required to use the core client. |
| `org.junit.jupiter:junit-jupiter` | `test` | Test framework. |

No JSON library, no HTTP client library — see `docs/SECURITY.md` and
the Javadoc on `internal.Json`/`internal.Transport` for the reasoning.
Run `mvn dependency:tree` and `mvn org.owasp:dependency-check-maven:check`
in CI to keep this list honest over time.

## Security

See [`docs/SECURITY.md`](docs/SECURITY.md) for the full audit notes.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `LoGuardValidationException: baseUrl must use https://` | Non-HTTPS `baseUrl` without `.allowInsecureTransport(true)` — only use that for local development. |
| Events not arriving via `eventAsync()` | Check logs at `FINE` level for `com.loguard.sdk` — failures are swallowed by design but logged. Confirm `shutdown()` hasn't already been called (post-shutdown, `eventAsync` is a no-op). |
| `LoGuardAuthException: Invalid API key` | Confirm the configured key matches the dashboard value exactly. |
| High memory / GC pressure under load | Check `maxQueueSize`/`maxBatchSize` are sized sensibly for your event volume; the queue is bounded but a very large `maxQueueSize` still means that many `Event` objects can be resident at once. |

## Production deployment checklist

- [ ] `LoGuardClient` constructed once and reused (Spring `@Bean`, static singleton, etc.) — not per request.
- [ ] `shutdown()` wired to your app's shutdown hook / `@PreDestroy` / `ServletContextListener#contextDestroyed`.
- [ ] `baseUrl` is `https://` (default) — do not set `allowInsecureTransport` in production.
- [ ] Trusted proxies configured if behind a load balancer, or `X-Forwarded-For`-based IP attribution can be spoofed.
- [ ] `track_headers`/`trackHeaders` left empty unless you've reviewed exactly which headers you're opting in to forward.
- [ ] `mvn dependency-check:check` (or your org's SCA tool) run in CI even though this SDK's own dependency surface is minimal — your full classpath is what matters.
