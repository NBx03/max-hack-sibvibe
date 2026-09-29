package ru.sibvibe.approval.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.sibvibe.approval.common.api.DemoActAsForbiddenException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.security.InitDataExpiredException;
import ru.sibvibe.approval.security.MaxIdentityVerifier;
import ru.sibvibe.approval.security.service.CurrentUserService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaxAuthenticationFilterTest {

    private final MaxIdentityVerifier verifier = mock(MaxIdentityVerifier.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesMaxInitDataAndDoesNotReadIdentityFromBody() throws Exception {
        var identity = new MaxIdentityVerifier.VerifiedIdentity("42", "Иван", 1_800_000_000L, null);
        CurrentUser current = currentUser(10, "Иван");
        when(verifier.verify("signed-data")).thenReturn(Optional.of(identity));
        when(currentUserService.resolve(identity, null)).thenReturn(current);
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "MaxInitData signed-data");
        request.setContentType("application/json");
        request.setContent("{\"max_user_id\":999999}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Object> principal = new AtomicReference<>();

        filter(false).doFilter(request, response, (req, res) ->
                principal.set(SecurityContextHolder.getContext().getAuthentication().getPrincipal()));

        assertThat(principal.get()).isSameAs(current);
        verify(currentUserService).resolve(identity, null);
    }

    @Test
    void rejectsMissingAuthorization() throws Exception {
        MockHttpServletResponse response = invoke(filter(false), request());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INIT_DATA_INVALID");
        verify(currentUserService, never()).resolve(any(), any());
    }

    @Test
    void mapsExpiredInitDataToDedicatedError() throws Exception {
        when(verifier.verify("expired")).thenThrow(new InitDataExpiredException());
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "MaxInitData expired");

        MockHttpServletResponse response = invoke(filter(false), request);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INIT_DATA_EXPIRED");
    }

    @Test
    void devHeaderWorksOnlyWhenDevProfileIsActive() throws Exception {
        MockHttpServletRequest productionRequest = request();
        productionRequest.addHeader("Authorization", "Dev 42");
        assertThat(invoke(filter(false), productionRequest).getStatus()).isEqualTo(401);

        var identity = new MaxIdentityVerifier.VerifiedIdentity("42", "Локальный пользователь 42",
                1_800_000_000L, null);
        when(currentUserService.resolve(eq(identity), isNull())).thenReturn(currentUser(10, "Локальный пользователь 42"));
        MockHttpServletRequest devRequest = request();
        devRequest.addHeader("Authorization", "Dev 42");

        assertThat(invoke(filter(true), devRequest).getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsUnsupportedBearerAuthorizationScheme() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer signed-data");

        MockHttpServletResponse response = invoke(filter(false), request);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(verifier, never()).verify(any());
    }

    @Test
    void rejectsForbiddenDemoActor() throws Exception {
        var identity = new MaxIdentityVerifier.VerifiedIdentity("42", "Иван", 1_800_000_000L, null);
        when(verifier.verify("signed-data")).thenReturn(Optional.of(identity));
        when(currentUserService.resolve(identity, "99")).thenThrow(new DemoActAsForbiddenException());
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "MaxInitData signed-data");
        request.addHeader("X-Demo-Act-As", "99");

        MockHttpServletResponse response = invoke(filter(false), request);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("DEMO_ACT_AS_FORBIDDEN");
    }

    @Test
    void mapsUnexpectedResolutionFailureToInternalError() throws Exception {
        var identity = new MaxIdentityVerifier.VerifiedIdentity("42", "Иван", 1_800_000_000L, null);
        when(verifier.verify("signed-data")).thenReturn(Optional.of(identity));
        when(currentUserService.resolve(identity, null)).thenThrow(new IllegalArgumentException("internal"));
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "MaxInitData signed-data");

        MockHttpServletResponse response = invoke(filter(false), request);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("INTERNAL_ERROR");
        assertThat(response.getContentAsString()).doesNotContain("internal");
    }

    @Test
    void exposesSwaggerOnlyInDevAndAllHealthEndpointsWithoutAuthentication() throws Exception {
        assertThat(invoke(filter(false), request("GET", "/v3/api-docs")).getStatus()).isEqualTo(401);
        assertThat(invoke(filter(true), request("GET", "/v3/api-docs")).getStatus()).isEqualTo(200);
        assertThat(invoke(filter(false), request("GET", "/actuator/health/liveness")).getStatus()).isEqualTo(200);
        assertThat(invoke(filter(false), request("GET", "/actuator/health/readiness")).getStatus()).isEqualTo(200);
    }

    @Test
    void botWebhookIsLeftToItsControllerBecauseMaxServerSendsNoInitData() throws Exception {
        assertThat(invoke(filter(false), request("POST", "/api/v1/bot/webhook")).getStatus()).isEqualTo(200);
        // только сам webhook и только POST: соседние пути по-прежнему требуют данные запуска
        assertThat(invoke(filter(false), request("GET", "/api/v1/bot/webhook")).getStatus()).isEqualTo(401);
        assertThat(invoke(filter(false), request("POST", "/api/v1/bot/webhook/x")).getStatus()).isEqualTo(401);
    }

    private MaxAuthenticationFilter filter(boolean devProfile) {
        return new MaxAuthenticationFilter(verifier, currentUserService, objectMapper, clock, devProfile);
    }

    private MockHttpServletRequest request() {
        return request("POST", "/api/v1/test");
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private MockHttpServletResponse invoke(MaxAuthenticationFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(200));
        return response;
    }

    private CurrentUser currentUser(long id, String name) {
        var user = new CurrentUser.UserIdentity(id, name);
        return new CurrentUser(user, user, null, null, Set.of(), false, null);
    }
}
