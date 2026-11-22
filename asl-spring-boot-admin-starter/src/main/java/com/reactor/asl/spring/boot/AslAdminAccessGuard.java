package com.reactor.asl.spring.boot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Single authorization and audit decision point for the ASL admin surface.
 *
 * <p>The guard is used by two layers. The servlet filter protects every request whose
 * <em>canonical</em> application path (decoded, without {@code ;} path parameters and with
 * repeated slashes collapsed) falls under the admin paths. The MVC interceptor protects every
 * request that Spring MVC actually dispatches to an admin controller, so authorization no longer
 * depends on how the URL was spelled.</p>
 */
final class AslAdminAccessGuard {
    static final String DECISION_ATTRIBUTE = AslAdminAccessGuard.class.getName() + ".decision";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final UrlPathHelper CANONICAL_PATHS = canonicalPathHelper();

    private final boolean allowUnauthenticated;
    private final boolean auditReads;
    private final AslAdminAccessPolicy accessPolicy;
    private final AslAdminAuditSink auditSink;
    private final boolean originCheckEnabled;
    private final java.util.Set<String> allowedOrigins;

    AslAdminAccessGuard(
            boolean allowUnauthenticated,
            boolean auditReads,
            AslAdminAccessPolicy accessPolicy,
            AslAdminAuditSink auditSink
    ) {
        this(allowUnauthenticated, auditReads, accessPolicy, auditSink, true, java.util.List.of());
    }

    AslAdminAccessGuard(
            boolean allowUnauthenticated,
            boolean auditReads,
            AslAdminAccessPolicy accessPolicy,
            AslAdminAuditSink auditSink,
            boolean originCheckEnabled,
            java.util.Collection<String> allowedOrigins
    ) {
        if (!allowUnauthenticated && accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy is required unless unauthenticated access is allowed");
        }
        this.allowUnauthenticated = allowUnauthenticated;
        this.auditReads = auditReads;
        this.accessPolicy = accessPolicy;
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.originCheckEnabled = originCheckEnabled;
        java.util.Set<String> normalized = new java.util.HashSet<>();
        for (String origin : allowedOrigins) {
            String value = normalizeOrigin(origin);
            if (value != null) {
                normalized.add(value);
            }
        }
        this.allowedOrigins = java.util.Set.copyOf(normalized);
    }

    static AslAdminAccessGuard from(
            AslAdminProperties properties,
            AslAdminAccessPolicy accessPolicy,
            AslAdminAuditSink auditSink
    ) {
        Objects.requireNonNull(properties, "properties");
        return new AslAdminAccessGuard(
                properties.getSecurity().isAllowUnauthenticated(),
                properties.getSecurity().isAuditReads(),
                accessPolicy,
                auditSink,
                properties.getSecurity().isOriginCheckEnabled(),
                properties.getSecurity().getAllowedOrigins()
        );
    }

    /**
     * Decoded application path without context path, {@code ;} parameters or repeated slashes.
     * This is the same view Spring MVC uses for handler matching.
     */
    static String canonicalPath(HttpServletRequest request) {
        String path = CANONICAL_PATHS.getPathWithinApplication(request);
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String collapsed = path.replaceAll("/{2,}", "/");
        return collapsed.startsWith("/") ? collapsed : "/" + collapsed;
    }

    /** Returns {@code true} when a decision for this request was already made by another layer. */
    static boolean alreadyDecided(HttpServletRequest request) {
        return request.getAttribute(DECISION_ATTRIBUTE) instanceof Decision;
    }

    /**
     * Authorizes the request. On denial the response is written and audited, and {@code false}
     * is returned. On success the decision is remembered on the request for completion auditing.
     */
    boolean authorize(
            HttpServletRequest request,
            HttpServletResponse response,
            boolean jsonResponse,
            Layer layer
    ) throws IOException {
        Object existing = request.getAttribute(DECISION_ATTRIBUTE);
        if (existing instanceof Decision decision) {
            return decision.authorized();
        }
        AslAdminAccessRequest accessRequest = new AslAdminAccessRequest(
                access(request.getMethod()),
                request.getMethod(),
                canonicalPath(request),
                safeRemoteAddress(request),
                principalName(request)
        );
        boolean crossOrigin = accessRequest.access() == AslAdminAccess.MUTATE && isCrossOrigin(request);
        boolean authorized = !crossOrigin && (allowUnauthenticated || accessPolicy.isAuthorized(accessRequest));
        request.setAttribute(DECISION_ATTRIBUTE, new Decision(accessRequest, authorized, layer));
        if (authorized) {
            return true;
        }
        int denialStatus = crossOrigin || request.getUserPrincipal() != null
                ? HttpServletResponse.SC_FORBIDDEN
                : HttpServletResponse.SC_UNAUTHORIZED;
        response.setStatus(denialStatus);
        if (jsonResponse) {
            response.setContentType(JSON_CONTENT_TYPE);
            response.getWriter().write("{\"error\":\"ASL admin access denied\"}");
        } else {
            response.setContentType("text/plain");
            response.getWriter().write("ASL admin access denied");
        }
        audit(accessRequest, false, denialStatus);
        return false;
    }

    /** Audits an authorized request once, from the layer that authorized it. */
    void auditCompletion(HttpServletRequest request, HttpServletResponse response, Layer layer) {
        if (!(request.getAttribute(DECISION_ATTRIBUTE) instanceof Decision decision)
                || !decision.authorized()
                || decision.layer() != layer) {
            return;
        }
        if (decision.request().access() == AslAdminAccess.MUTATE || auditReads) {
            audit(decision.request(), true, response.getStatus());
        }
    }

    private void audit(AslAdminAccessRequest request, boolean authorized, int responseStatus) {
        auditSink.record(new AslAdminAuditEvent(
                Instant.now(),
                request.access(),
                request.httpMethod(),
                request.path(),
                request.remoteAddress(),
                request.principalName(),
                authorized,
                responseStatus
        ));
    }

    /**
     * CSRF defence for cookie-authenticated operators: a browser always sends {@code Origin} (or at
     * least {@code Referer}) on cross-site state-changing requests. Non-browser clients send neither
     * and are governed by the access policy alone.
     */
    private boolean isCrossOrigin(HttpServletRequest request) {
        if (!originCheckEnabled) {
            return false;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            origin = request.getHeader("Referer");
        }
        if (origin == null || origin.isBlank()) {
            return false;
        }
        String normalized = normalizeOrigin(origin);
        if (normalized == null) {
            return true; // "null" origin (sandboxed frame, file://) or malformed header
        }
        if (allowedOrigins.contains(normalized)) {
            return false;
        }
        String authority = normalized.substring(normalized.indexOf("://") + 3);
        return !sameAuthority(authority, request.getHeader("Host"))
                && !sameAuthority(authority, firstForwardedHost(request.getHeader("X-Forwarded-Host")));
    }

    private static boolean sameAuthority(String originAuthority, String host) {
        return host != null && !host.isBlank() && originAuthority.equalsIgnoreCase(host.trim());
    }

    private static String firstForwardedHost(String forwardedHost) {
        if (forwardedHost == null) {
            return null;
        }
        int comma = forwardedHost.indexOf(',');
        return comma < 0 ? forwardedHost : forwardedHost.substring(0, comma);
    }

    /** {@code scheme://host[:port]} in lower case, or {@code null} when not a usable origin. */
    static String normalizeOrigin(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(value.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String authority = uri.getHost().toLowerCase(Locale.ROOT) + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + authority;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    static AslAdminAccess access(String method) {
        String normalized = method == null ? "" : method.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "GET", "HEAD", "OPTIONS" -> AslAdminAccess.READ;
            default -> AslAdminAccess.MUTATE;
        };
    }

    private static String principalName(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        return principal == null ? "anonymous" : principal.getName();
    }

    private static String safeRemoteAddress(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    private static UrlPathHelper canonicalPathHelper() {
        UrlPathHelper helper = new UrlPathHelper();
        helper.setUrlDecode(true);
        helper.setRemoveSemicolonContent(true);
        helper.setAlwaysUseFullPath(true);
        return helper;
    }

    enum Layer {
        FILTER,
        HANDLER
    }

    private record Decision(AslAdminAccessRequest request, boolean authorized, Layer layer) {
    }
}
