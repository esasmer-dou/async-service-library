package com.reactor.asl.spring.boot;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * First authorization layer for the ASL admin surface.
 *
 * <p>Path matching uses the canonical application path (decoded, without {@code ;} path
 * parameters, repeated slashes collapsed), so spellings such as {@code /asl;x=1/api} or
 * {@code /%61sl/api} cannot skip the check. {@link AslAdminAuthorizationInterceptor} adds a second,
 * handler-based layer for anything that still reaches an admin controller.</p>
 */
final class AslAdminSecurityFilter extends OncePerRequestFilter {
    private final String adminPath;
    private final String apiPath;
    private final AslAdminAccessGuard guard;

    AslAdminSecurityFilter(
            AslAdminProperties properties,
            AslAdminAccessPolicy accessPolicy,
            AslAdminAuditSink auditSink
    ) {
        this(properties, AslAdminAccessGuard.from(properties, accessPolicy, auditSink));
    }

    AslAdminSecurityFilter(AslAdminProperties properties, AslAdminAccessGuard guard) {
        Objects.requireNonNull(properties, "properties");
        this.adminPath = normalizePath(properties.getPath());
        this.apiPath = normalizePath(properties.getApiPath());
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = AslAdminAccessGuard.canonicalPath(request);
        return !matches(path, adminPath) && !matches(path, apiPath);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean jsonResponse = matches(AslAdminAccessGuard.canonicalPath(request), apiPath);
        if (!guard.authorize(request, response, jsonResponse, AslAdminAccessGuard.Layer.FILTER)) {
            return;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            guard.auditCompletion(request, response, AslAdminAccessGuard.Layer.FILTER);
        }
    }

    private static boolean matches(String requestPath, String configuredPath) {
        return requestPath.equals(configuredPath) || requestPath.startsWith(configuredPath + "/");
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("ASL admin paths must start with '/'");
        }
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
