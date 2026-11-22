package me.lendev.flow.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class OpenApiTrafficFilterTest {

    @Test
    void disabledPublicRoutesStayClosedWithoutTouchingRedis() throws Exception {
        var limits = mock(OpenApiRateLimits.class);
        var calls = mock(OpenApiCalls.class);
        var filter = new OpenApiTrafficFilter(settings(false), new OpenApiResponses(new ObjectMapper()), calls, limits);
        var reached = new AtomicBoolean();
        for (String path : Set.of("/oauth2/token", "/open/v1/trading-calendar/year")) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", path), response, (request, res) -> reached.set(true));
            assertEquals(404, response.getStatus());
            assertEquals("no-store", response.getHeader("Cache-Control"));
        }
        assertFalse(reached.get());
        verifyNoInteractions(limits, calls);
    }

    @Test
    void onlyTrustedPeerAddressesCanSetForwardedIpAndSigningFailsClosed() throws Exception {
        var limits = mock(OpenApiRateLimits.class);
        var filter = new OpenApiTrafficFilter(settings(true), new OpenApiResponses(new ObjectMapper()), mock(OpenApiCalls.class), limits);
        var trusted = new MockHttpServletRequest("POST", "/oauth2/token");
        trusted.setRemoteAddr("192.0.2.1");
        trusted.addHeader("X-Real-IP", "203.0.113.4");
        doThrow(OpenApiException.limited(5)).when(limits).signingIp("203.0.113.4");
        var response = new MockHttpServletResponse();
        filter.doFilter(trusted, response, (request, res) -> {
        });
        assertEquals(429, response.getStatus());
        assertEquals("5", response.getHeader("Retry-After"));

        var untrusted = new MockHttpServletRequest("POST", "/oauth2/token");
        untrusted.setRemoteAddr("192.0.2.7");
        untrusted.addHeader("X-Real-IP", "203.0.113.4");
        doThrow(new DataAccessResourceFailureException("Redis unavailable")).when(limits).signingIp("192.0.2.7");
        response = new MockHttpServletResponse();
        filter.doFilter(untrusted, response, (request, res) -> {
        });
        verify(limits).signingIp("192.0.2.7");
        assertEquals(503, response.getStatus());
        assertEquals("temporarily_unavailable", new ObjectMapper().readTree(response.getContentAsString()).path("error").asString());
    }

    private static OpenApiProperties settings(boolean enabled) {
        return new OpenApiProperties(enabled, "test", "https://example.test", 1800, 60, 30, 30, Set.of("192.0.2.1"));
    }
}
