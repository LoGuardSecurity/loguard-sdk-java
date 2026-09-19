package com.loguard.sdk.integration.servlet;

import com.loguard.sdk.LoGuardClient;
import com.loguard.sdk.exceptions.LoGuardException;
import com.loguard.sdk.http.ClientIp;
import com.loguard.sdk.http.HeaderPolicy;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Jakarta Servlet {@link Filter} that automatically reports HTTP error
 * responses as LoGuard security events — the Java equivalent of the
 * Python SDK's FastAPI middleware and the PHP SDK's Laravel
 * middleware.
 *
 * Deliberately built against {@code jakarta.servlet-api} (a
 * {@code provided}-scope dependency — see pom.xml) rather than any
 * specific framework, so it works unmodified under plain Servlet
 * containers (Tomcat, Jetty, Undertow) and, since Spring MVC and
 * Spring Boot's embedded containers are themselves Servlet-based,
 * under Spring without any Spring-specific dependency at all:
 *
 * <pre>{@code
 * @Bean
 * public FilterRegistrationBean<LoGuardServletFilter> loguardFilter(LoGuardClient client) {
 *     FilterRegistrationBean<LoGuardServletFilter> reg = new FilterRegistrationBean<>();
 *     reg.setFilter(new LoGuardServletFilter(client));
 *     reg.addUrlPatterns("/*");
 *     return reg;
 * }
 * }</pre>
 *
 * Or, in a plain {@code web.xml} / embedded Tomcat context, register
 * it as any other {@link Filter}.
 *
 * Design notes (matching the other SDKs' middleware):
 *  - Reporting is done via {@link LoGuardClient#eventAsync} — never
 *    blocks the request thread.
 *  - {@code X-Forwarded-For} is resolved via {@link ClientIp}
 *    (trusted-proxy aware, secure by default).
 *  - Headers are only forwarded if explicitly configured, and a
 *    hard-coded deny-list ({@link HeaderPolicy#FORBIDDEN_HEADERS}) is
 *    enforced regardless of configuration.
 *  - Any SDK failure is caught and logged, never allowed to affect
 *    the response or propagate as a servlet exception.
 */
public final class LoGuardServletFilter implements Filter {

    private static final Logger LOG = Logger.getLogger("com.loguard.sdk.servlet");
    private static final Set<Integer> DEFAULT_TRACK_STATUSES = Set.of(400, 401, 403, 404, 429, 500, 502, 503);

    private final LoGuardClient client;
    private final Set<Integer> trackStatuses;
    private final List<String> trackHeaders;
    private final List<String> trustedProxies;
    private final Function<HttpServletRequest, String> userIdResolver;

    public LoGuardServletFilter(LoGuardClient client) {
        this(client, DEFAULT_TRACK_STATUSES, Collections.emptyList(), Collections.emptyList(), LoGuardServletFilter::defaultUserId);
    }

    public LoGuardServletFilter(LoGuardClient client,
                                 Set<Integer> trackStatuses,
                                 List<String> trackHeaders,
                                 List<String> trustedProxies,
                                 Function<HttpServletRequest, String> userIdResolver) {
        this.client = client;
        this.trackStatuses = trackStatuses;
        this.trackHeaders = HeaderPolicy.sanitizeRequested(trackHeaders);
        this.trustedProxies = trustedProxies;
        this.userIdResolver = userIdResolver != null ? userIdResolver : LoGuardServletFilter::defaultUserId;
    }

    private static String defaultUserId(HttpServletRequest request) {
        Principal p = request.getUserPrincipal();
        return p != null ? p.getName() : null;
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // Nothing to do -- the client is already constructed/configured by the time this filter runs.
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
        throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest) || !(servletResponse instanceof HttpServletResponse)) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        // Resolve IP up front: X-Forwarded-For must be evaluated against
        // the ORIGINAL direct peer (request.getRemoteAddr()), which some
        // containers/other filters may rewrite later in the chain.
        String ip = ClientIp.resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), trustedProxies);

        try {
            chain.doFilter(request, response);
        } finally {
            try {
                reportIfNeeded(request, response, ip);
            } catch (RuntimeException e) {
                // A LoGuard failure must never surface as a servlet-level error.
                LOG.log(Level.FINE, "LoGuardServletFilter: failed to report event", e);
            }
        }
    }

    private void reportIfNeeded(HttpServletRequest request, HttpServletResponse response, String ip) {
        int status = response.getStatus();
        if (!trackStatuses.contains(status)) {
            return;
        }

        String userId;
        try {
            userId = userIdResolver.apply(request);
        } catch (RuntimeException e) {
            userId = null;
        }

        Map<String, Object> collected = new LinkedHashMap<>(HeaderPolicy.collect(trackHeaders, request::getHeader));
        Map<String, Object> meta = new LinkedHashMap<>();
        if (!collected.isEmpty()) {
            meta.put("headers", collected);
        }

        String eventType = status == 401 ? "login_failed" : "http_error";
        String path = request.getRequestURI();

        try {
            client.eventAsync(eventType, ip, path, status, userId, null, meta.isEmpty() ? null : meta, null);
        } catch (LoGuardException e) {
            LOG.log(Level.FINE, "LoGuardServletFilter: eventAsync failed", e);
        }
    }

    @Override
    public void destroy() {
        // The filter does not own the LoGuardClient's lifecycle -- the
        // application constructed and should shut down the client
        // itself (e.g. a Spring @PreDestroy on the bean), since the
        // same client is typically shared outside the web layer too
        // (background jobs, non-HTTP code paths).
    }
}
