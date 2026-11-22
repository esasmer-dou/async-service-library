package com.reactor.asl.spring.boot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

/**
 * Second authorization layer: every request that Spring MVC dispatches to an ASL admin controller
 * is authorized here, independent of the URL spelling. Requests already decided by
 * {@link AslAdminSecurityFilter} are not evaluated or audited twice.
 */
final class AslAdminAuthorizationInterceptor implements HandlerInterceptor {
    private final AslAdminAccessGuard guard;

    AslAdminAuthorizationInterceptor(AslAdminAccessGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!isAdminHandler(handler)) {
            return true;
        }
        // When the filter already decided, authorize() returns that decision without re-evaluating.
        return guard.authorize(request, response, isRestHandler(handler), AslAdminAccessGuard.Layer.HANDLER);
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        if (isAdminHandler(handler)) {
            guard.auditCompletion(request, response, AslAdminAccessGuard.Layer.HANDLER);
        }
    }

    static boolean isAdminHandler(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return false;
        }
        Class<?> beanType = handlerMethod.getBeanType();
        return AslAdminRestController.class.isAssignableFrom(beanType)
                || AslAdminPageController.class.isAssignableFrom(beanType);
    }

    private static boolean isRestHandler(Object handler) {
        return handler instanceof HandlerMethod handlerMethod
                && AslAdminRestController.class.isAssignableFrom(handlerMethod.getBeanType());
    }
}
