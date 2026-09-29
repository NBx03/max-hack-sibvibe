package ru.sibvibe.approval.security.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import ru.sibvibe.approval.common.api.RestExceptionHandler;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.security.service.SessionViewService;

import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoSandboxControllerTest {

    @Test
    void bothEndpointsReturnNotFoundWhenDemoModeIsDisabled() throws Exception {
        SessionViewService service = mock(SessionViewService.class);
        when(service.demoMode()).thenReturn(false);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoSandboxController(service))
                .setControllerAdvice(new RestExceptionHandler())
                .setCustomArgumentResolvers(currentUserResolver())
                .build();

        mvc.perform(get("/api/v1/demo/sandbox"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/v1/demo/sandbox"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private HandlerMethodArgumentResolver currentUserResolver() {
        var identity = new CurrentUser.UserIdentity(1, "Проверяющий");
        CurrentUser currentUser = new CurrentUser(identity, identity, null, null, Set.of(), false, null);
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return currentUser;
            }
        };
    }
}
