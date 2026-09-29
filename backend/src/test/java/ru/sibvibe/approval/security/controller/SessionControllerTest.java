package ru.sibvibe.approval.security.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import ru.sibvibe.approval.common.api.RestExceptionHandler;
import ru.sibvibe.approval.common.api.DemoActAsForbiddenException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.security.MaxIdentityVerifier;
import ru.sibvibe.approval.security.filter.MaxAuthenticationFilter;
import ru.sibvibe.approval.security.service.CurrentUserService;
import ru.sibvibe.approval.security.service.SessionViewService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionControllerTest {

    @Test
    void mapsDemoActorRevalidationFailureAfterSecurityFilterToForbidden() throws Exception {
        var identity = new MaxIdentityVerifier.VerifiedIdentity("42", "Проверяющий", 1_800_000_000L, null);
        var userIdentity = new CurrentUser.UserIdentity(7, "Проверяющий");
        var currentUser = new CurrentUser(userIdentity, userIdentity, null, null, Set.of(), false, null);
        MaxIdentityVerifier verifier = rawInitData -> Optional.of(identity);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.resolve(identity, null)).thenReturn(currentUser);
        SessionViewService sessionViewService = mock(SessionViewService.class);
        when(sessionViewService.me(currentUser)).thenThrow(new DemoActAsForbiddenException());
        var filter = new MaxAuthenticationFilter(
                verifier,
                currentUserService,
                new ObjectMapper(),
                Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC),
                false);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SessionController(sessionViewService))
                .setControllerAdvice(new RestExceptionHandler())
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .addFilters(filter)
                .build();

        mvc.perform(get("/api/v1/me")
                        .header("Authorization", "MaxInitData signed-data"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMO_ACT_AS_FORBIDDEN"))
                .andExpect(jsonPath("$.message")
                        .value("Нельзя действовать от имени этого пользователя"));
        verify(sessionViewService).me(currentUser);
    }

    private HandlerMethodArgumentResolver authenticationPrincipalResolver() {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            }
        };
    }
}
