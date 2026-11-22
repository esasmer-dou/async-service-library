package com.reactor.asl.spring.boot;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslAdminSecurityFilterTest {

    @Test
    void deniesAnonymousAdminMutationWithUnauthorizedAndAuditsIt() throws Exception {
        AslAdminProperties properties = properties(false, false);
        List<AslAdminAuditEvent> events = new ArrayList<>();
        AslAdminSecurityFilter filter = new AslAdminSecurityFilter(properties, request -> false, events::add);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/asl/api/config/runtime");
        request.setRemoteAddr("10.0.0.7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain chain = new TrackingFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("access denied"));
        assertFalse(chain.called);
        assertEquals(1, events.size());
        assertEquals(AslAdminAccess.MUTATE, events.getFirst().access());
        assertFalse(events.getFirst().authorized());
    }

    @Test
    void deniesAuthenticatedButUnauthorizedAdminMutationWithForbidden() throws Exception {
        AslAdminProperties properties = properties(false, false);
        List<AslAdminAuditEvent> events = new ArrayList<>();
        AslAdminSecurityFilter filter = new AslAdminSecurityFilter(properties, request -> false, events::add);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/asl/api/config/runtime");
        request.setUserPrincipal((Principal) () -> "operator");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new TrackingFilterChain());

        assertEquals(403, response.getStatus());
        assertEquals("operator", events.getFirst().principalName());
        assertFalse(events.getFirst().authorized());
    }

    @Test
    void protectsAdminPathsWhenTheApplicationUsesAContextPath() throws Exception {
        AslAdminProperties properties = properties(false, false);
        List<AslAdminAuditEvent> events = new ArrayList<>();
        AslAdminSecurityFilter filter = new AslAdminSecurityFilter(properties, request -> false, events::add);
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/app/asl/api/config/runtime");
        request.setContextPath("/app");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain chain = new TrackingFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertFalse(chain.called);
        assertEquals("/asl/api/config/runtime", events.getFirst().path());
    }

    @Test
    void allowsAuthorizedReadWithoutReadAuditByDefault() throws Exception {
        AslAdminProperties properties = properties(false, false);
        List<AslAdminAuditEvent> events = new ArrayList<>();
        AslAdminSecurityFilter filter = new AslAdminSecurityFilter(properties, request -> true, events::add);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/asl/api/summary");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain chain = new TrackingFilterChain();

        filter.doFilter(request, response, chain);

        assertTrue(chain.called);
        assertTrue(events.isEmpty());
    }

    @Test
    void explicitDemoBypassStillAuditsMutations() throws Exception {
        AslAdminProperties properties = properties(true, false);
        List<AslAdminAuditEvent> events = new ArrayList<>();
        AslAdminSecurityFilter filter = new AslAdminSecurityFilter(properties, null, events::add);
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/asl/api/services/demo/methods/run/buffer");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingFilterChain chain = new TrackingFilterChain();

        filter.doFilter(request, response, chain);

        assertTrue(chain.called);
        assertEquals(1, events.size());
        assertTrue(events.getFirst().authorized());
    }

    @Test
    void protectsNonCanonicalAdminPathSpellings() throws Exception {
        AslAdminProperties properties = properties(false, false);
        for (String uri : List.of(
                "/asl;x=1/api/config/runtime",
                "/%61sl/api/config/runtime",
                "//asl/api/config/runtime",
                "/asl/api;x=1/config/runtime"
        )) {
            List<AslAdminAuditEvent> events = new ArrayList<>();
            AslAdminSecurityFilter filter = new AslAdminSecurityFilter(properties, request -> false, events::add);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
            MockHttpServletResponse response = new MockHttpServletResponse();
            TrackingFilterChain chain = new TrackingFilterChain();

            filter.doFilter(request, response, chain);

            assertEquals(401, response.getStatus(), uri);
            assertFalse(chain.called, uri);
            assertEquals("/asl/api/config/runtime", events.getFirst().path(), uri);
        }
    }

    @Test
    void interceptorAuthorizesAdminHandlersReachedOutsideFilteredPaths() throws Exception {
        List<AslAdminAuditEvent> events = new ArrayList<>();
        AslAdminAccessGuard guard = new AslAdminAccessGuard(false, false, request -> false, events::add);
        AslAdminAuthorizationInterceptor interceptor = new AslAdminAuthorizationInterceptor(guard);
        AslAdminRestController controller = new AslAdminRestController(null);
        org.springframework.web.method.HandlerMethod handler = new org.springframework.web.method.HandlerMethod(
                controller,
                AslAdminRestController.class.getMethod("summary")
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/unexpected/alias");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, handler));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("access denied"));
        assertEquals(1, events.size());
    }

    @Test
    void interceptorDoesNotReevaluateOrDoubleAuditFilterDecisions() throws Exception {
        List<AslAdminAuditEvent> events = new ArrayList<>();
        int[] policyCalls = {0};
        AslAdminAccessGuard guard = new AslAdminAccessGuard(false, false, request -> {
            policyCalls[0]++;
            return true;
        }, events::add);
        AslAdminSecurityFilter filter = new AslAdminSecurityFilter(properties(false, false), guard);
        AslAdminAuthorizationInterceptor interceptor = new AslAdminAuthorizationInterceptor(guard);
        org.springframework.web.method.HandlerMethod handler = new org.springframework.web.method.HandlerMethod(
                new AslAdminRestController(null),
                AslAdminRestController.class.getMethod("summary")
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/asl/api/config/runtime");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            try {
                assertTrue(interceptor.preHandle(request, response, handler));
                interceptor.afterCompletion(request, response, handler, null);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });

        assertEquals(1, policyCalls[0]);
        assertEquals(1, events.size());
        assertTrue(events.getFirst().authorized());
    }

    @Test
    void rejectsCrossOriginMutationsEvenWhenThePolicyAllows() throws Exception {
        List<AslAdminAuditEvent> events = new ArrayList<>();
        AslAdminAccessGuard guard = new AslAdminAccessGuard(
                false, false, request -> true, events::add, true, List.of("https://ops.example.com"));
        AslAdminSecurityFilter filter = new AslAdminSecurityFilter(properties(false, false), guard);

        record Case(String method, String origin, String referer, int expectedStatus, boolean chainCalled) {
        }
        for (Case c : List.of(
                new Case("POST", "https://evil.example", null, 403, false),
                new Case("POST", "null", null, 403, false),
                new Case("POST", null, "https://evil.example/page", 403, false),
                new Case("POST", "http://localhost:8080", null, 200, true),
                new Case("POST", "https://ops.example.com", null, 200, true),
                new Case("POST", null, null, 200, true),
                new Case("GET", "https://evil.example", null, 200, true)
        )) {
            MockHttpServletRequest request = new MockHttpServletRequest(c.method(), "/asl/api/config/runtime");
            request.addHeader("Host", "localhost:8080");
            if (c.origin() != null) {
                request.addHeader("Origin", c.origin());
            }
            if (c.referer() != null) {
                request.addHeader("Referer", c.referer());
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            TrackingFilterChain chain = new TrackingFilterChain();

            filter.doFilter(request, response, chain);

            assertEquals(c.expectedStatus(), response.getStatus(), c.toString());
            assertEquals(c.chainCalled(), chain.called, c.toString());
        }
        assertTrue(events.stream().anyMatch(event -> !event.authorized() && event.responseStatus() == 403));
    }

    @Test
    void originCheckCanBeDisabled() throws Exception {
        AslAdminAccessGuard guard = new AslAdminAccessGuard(false, false, request -> true, event -> { }, false, List.of());
        AslAdminSecurityFilter filter = new AslAdminSecurityFilter(properties(false, false), guard);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/asl/api/config/runtime");
        request.addHeader("Host", "localhost:8080");
        request.addHeader("Origin", "https://evil.example");
        TrackingFilterChain chain = new TrackingFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertTrue(chain.called);
    }

    private static AslAdminProperties properties(boolean allowUnauthenticated, boolean auditReads) {
        AslAdminProperties properties = new AslAdminProperties();
        properties.setEnabled(true);
        properties.getSecurity().setAllowUnauthenticated(allowUnauthenticated);
        properties.getSecurity().setAuditReads(auditReads);
        return properties;
    }

    private static final class TrackingFilterChain implements FilterChain {
        private boolean called;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            called = true;
        }
    }
}
