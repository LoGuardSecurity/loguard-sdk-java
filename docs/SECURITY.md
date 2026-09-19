# LoGuard Java SDK — Security Notes

Not a claim of "no vulnerabilities" — a record of the attack classes
this implementation was checked against, what's mitigated and how
(with a pointer to the test that exercises it), and what's out of
scope for a client SDK to solve on its own.

## Threat classes reviewed

| Class | Status | Notes |
|---|---|---|
| SSRF via `baseUrl` | Mitigated at config layer, not eliminated | `baseUrl` is developer-supplied config, not attacker input, in any normal deployment. `LoGuardConfig` enforces `https://` by default. `java.net.http.HttpClient` only ever dispatches `http`/`https` requests by construction (there is no scheme-pluggable transport in the JDK client), so a misconfigured `baseUrl` can't reach a non-HTTP scheme. If your app lets *end users* influence `baseUrl` at runtime, that's an application-level SSRF risk no SDK config can fix. |
| Unsafe redirects / credential leak via redirect | Mitigated | `HttpClient.Redirect.NEVER` set on the shared client. A 3xx from the ingest endpoint is never auto-followed. Verified in `TransportIntegrationTest#redirectIsNeverFollowed`. |
| TLS verification / certificate validation | Enforced via the JDK's default SSLContext, not configurable off | The SDK does not construct a custom `SSLContext`/`TrustManager` and provides no API to install a permissive one. `allowInsecureTransport` only permits a plain-`http://` `baseUrl` for local development; irrelevant to TLS verification (no TLS exists for `http://`). |
| API key / secret leakage in logs | Mitigated | The SDK never logs headers, request, or response bodies at any level above `FINE`, and `FINE`-level messages log exception summaries, not raw secrets. `LoGuardAuthException` messages are static strings that never interpolate the key. |
| Sensitive data leakage via forwarded headers | Mitigated in code, not just docs | `HeaderPolicy.FORBIDDEN_HEADERS` is stripped from any `trackHeaders` list even if explicitly requested — `HeaderPolicyTest#forbiddenHeadersAreAlwaysStrippedEvenIfRequested`. No request body or query string is ever collected automatically by `LoGuardServletFilter`. |
| IP spoofing via `X-Forwarded-For` | Mitigated, secure by default | `ClientIp.resolve()` only trusts `X-Forwarded-For` when the direct TCP peer (`HttpServletRequest.getRemoteAddr()`) is in an explicitly configured trust list (IP or CIDR). Empty config (the default) means the header is never trusted — `ClientIpTest`. |
| Header injection (CRLF injection into outbound headers) | Mitigated | Outbound headers sent to LoGuard come only from `Signing.sign()` (SDK-controlled values) or the fixed `X-Api-Key`/`Content-Type`/`User-Agent` set in `LoGuardClient.defaultHeaders()`. Forwarded *application* header values (`meta.headers` in the servlet filter) are serialized as JSON string values via `internal.Json`, which escapes control characters — they never become raw outbound HTTP header lines. |
| Request smuggling-related behavior | Not directly applicable | This SDK is an HTTP client, not a proxy/server; it does not parse or re-emit raw HTTP framing from untrusted input. |
| Unsafe (de)serialization | Mitigated | Only the SDK's own `internal.Json` reader/writer is used — no Java native serialization (`ObjectInputStream`/`readObject`), no reflection-based deserialization of server-controlled data into arbitrary types. Parsed JSON only ever becomes `Map`/`List`/`String`/`Double`/`Boolean`/`null`. |
| Path traversal | Not applicable | The SDK does not read/write files based on request/response content. |
| Command execution / code injection / template injection | Not applicable | No `ProcessBuilder`/`Runtime.exec`, no `eval`-equivalent, no template engine anywhere in `src/main`. |
| Insecure defaults | Reviewed | HTTPS required by default; TLS verification always on (JDK default); redirects never followed; sensitive headers never forwarded; `X-Forwarded-For` never trusted by default; retries bounded (default 3); response size capped (5 MiB); queue bounded (default 2000); exactly one background thread per client. |
| Dependency vulnerabilities | Minimized by near-zero dependency surface | Core module requires **no** runtime dependency beyond the JDK itself. `jakarta.servlet-api` is `provided`-scope and only needed to compile the optional Servlet integration — it is not bundled or required at runtime by applications that don't use that package. Run `mvn dependency-check:check` / `mvn dependency:tree` in CI regardless — that's a supply-chain check this document can't perform for you. |
| Malicious/malformed server responses | Mitigated | `internal.Json.parse()` throws a controlled `JsonException` (never an unchecked parser crash) on malformed input; `Transport.raiseForStatus()` treats a decode failure as `null` rather than propagating — `TransportIntegrationTest#malformedJsonResponseDoesNotCrashTheClient`. |
| Oversized responses / memory exhaustion | Mitigated | A custom `BodySubscriber` (`Transport.BoundedStringSubscriber`) cancels the subscription and aborts once more than `MAX_RESPONSE_BYTES` (5 MiB) has been received, instead of buffering an attacker-controlled amount of data — `TransportIntegrationTest#oversizedResponseIsAbortedNotBuffered`. |
| Oversized/deeply-nested JSON (parser DoS) | Mitigated | `internal.Json` bounds both total input length (`MAX_INPUT_LENGTH`, 8 MiB) and recursion depth (`MAX_DEPTH`, 64) — `JsonTest#deeplyNestedInputIsRejectedNotStackOverflowed`, `JsonTest#oversizedInputIsRejected`. |
| Oversized events / queue exhaustion | Mitigated | Event `path` truncated to 1024 chars, `type`/`ip`/`service` to 64, `userId` to 128, forwarded header values to 512 — matching every other LoGuard SDK's limits exactly. `eventAsync()`'s `ArrayBlockingQueue` is bounded (`maxQueueSize`, default 2000); once full, the newest event is dropped (optionally observed via `onDropped`) rather than growing unbounded — `LoGuardClientConcurrencyTest#fullQueueDropsEventsInsteadOfBlockingCaller`. |
| Retry storms | Mitigated | Retries are bounded (`retries`, default 3) with linear backoff (`400ms * attempt`). No unbounded/uncapped retry loop exists. |
| Uncontrolled thread creation / resource exhaustion | Mitigated | Exactly one background flush thread is created per `LoGuardClient` instance (started lazily, `daemon = true`), regardless of how many producer threads call `eventAsync()` concurrently — verified in `LoGuardClientConcurrencyTest#manyThreadsCallingEventAsyncNeverThrowAndStayBounded` by asserting there is never more than one thread named `loguard-sdk-flush-worker` live at a time. HTTP I/O reuses one pooled `HttpClient`, not a connection/thread per request. |
| Unbounded buffering | Mitigated | Both the event queue (`maxQueueSize`) and the HTTP response reader (`MAX_RESPONSE_BYTES`) are hard-capped; batches sent per HTTP call are capped at `maxBatchSize` (default 50). |
| Race conditions / concurrency issues | Reviewed | `LoGuardClient`'s mutable state is limited to: an `ArrayBlockingQueue` (thread-safe by construction), two `AtomicBoolean`s (`workerStarted`, `shuttingDown`) governing worker lifecycle, and a `CountDownLatch` for shutdown synchronization — no unsynchronized shared mutable state. `Transport`/`HttpClient` are stateless with respect to any single request beyond the shared connection pool, which the JDK itself guarantees is thread-safe. Exercised under concurrent load in `LoGuardClientConcurrencyTest`. |
| Improper error handling / information disclosure | Mitigated | Exceptions carry short, truncated (400-char) response snippets for debugging, never full bodies or secrets. `LoGuardServletFilter` catches all `RuntimeException`s from reporting and never lets them propagate as a servlet-level error affecting the actual HTTP response. |

## What this SDK does **not** try to solve

- **Application-level authorization** — the SDK reports events; it
  does not enforce access control. The Ed25519 "Shield" sentinel-verdict
  feature present in some sibling SDKs (Node/Go/C#) was intentionally
  **not** ported here — see the parity notes in the top-level
  engineering summary for why, and the Python SDK's own removal of
  the equivalent `shield.py` in favor of a server-side agent.
- **Multi-tenant secret isolation within one JVM** — if multiple
  tenants share a process, construct one `LoGuardClient` per tenant
  with that tenant's own `LoGuardConfig`; the SDK has no tenant
  concept of its own.
- **Supply-chain integrity of the JDK/Maven ecosystem itself** — run
  `mvn dependency-check:check`, pin plugin versions, verify checksums
  in CI; standard Java hygiene outside this SDK's scope.

## Reporting a vulnerability

Please report suspected vulnerabilities in this SDK privately rather
than as a public GitHub issue — see `SECURITY.md` in the main LoGuard
backend repository for the current disclosure process and contact.
