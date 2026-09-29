package ru.sibvibe.approval.common.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RestExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new RestExceptionHandler())
                .build();
    }

    @Test
    void malformedJsonReturnsValidationFailed() throws Exception {
        mockMvc.perform(post("/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isMap());
    }

    @Test
    void invalidRequestBodyReturnsValidationFailed() throws Exception {
        mockMvc.perform(post("/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isMap());
    }

    @Test
    void missingMultipartPartReturnsValidationFailed() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/multipart"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void nonNumericPathVariableReturnsValidationFailedInsteadOfServerError() throws Exception {
        mockMvc.perform(get("/test/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unsupportedMediaTypeHasExplicitValidationCode() throws Exception {
        mockMvc.perform(post("/test")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("value"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void wrongMethodHasOwnCodeInsteadOfInternalError() throws Exception {
        mockMvc.perform(get("/test"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void otherSpringClientErrorsAreBadRequestNotInternalError() throws Exception {
        mockMvc.perform(get("/gone"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @RestController
    private static class TestController {

        @PostMapping(value = "/test", consumes = MediaType.APPLICATION_JSON_VALUE)
        void accept(@Valid @RequestBody TestRequest request) {
        }

        @PostMapping(value = "/multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        void multipart(@RequestPart("main") byte[] main) {
        }

        @GetMapping("/gone")
        void gone() {
            throw new ResponseStatusException(HttpStatus.GONE);
        }

        @GetMapping("/test/{id}")
        void byId(@PathVariable long id) {
        }
    }

    private record TestRequest(@NotBlank String value) {
    }
}
