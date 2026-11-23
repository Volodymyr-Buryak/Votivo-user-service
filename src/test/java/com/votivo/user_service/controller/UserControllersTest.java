package com.votivo.user_service.controller;

import com.votivo.user_service.controller.advice.ApiExceptionHandler;
import com.votivo.user_service.dto.request.UpdateProfileRequest;
import com.votivo.user_service.dto.response.UserPageResponse;
import com.votivo.user_service.dto.response.UserResponse;
import com.votivo.user_service.dto.response.UserHeaderResponse;
import com.votivo.user_service.entity.UserRole;
import com.votivo.user_service.service.ProfileService;
import com.votivo.user_service.service.exception.IdentityAccessException;
import com.votivo.user_service.service.exception.ProfileAccessDeniedException;
import com.votivo.user_service.service.exception.ProfileConflictException;
import com.votivo.user_service.service.exception.UserNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserControllersTest {
    private final UUID actor = UUID.randomUUID();
    private final ProfileService profiles = mock(ProfileService.class);
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setup() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        JsonMapper mapper = JsonMapper.builder()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        mvc = MockMvcBuilders.standaloneSetup(new UserController(profiles), new AdminUserController(profiles))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setValidator(validator)
                .build();
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "RS256")
                .subject(actor.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        validator.close();
    }

    @Test
    void ownEndpointsAlwaysUseJwtSubject() throws Exception {
        when(profiles.getOwn(actor)).thenReturn(response(actor));
        when(profiles.updateOwn(eq(actor), any())).thenReturn(response(actor));
        when(profiles.getOwnHeader(actor)).thenReturn(new UserHeaderResponse("Ada", "Lovelace"));
        mvc.perform(get("/api/v1/users/me/header").param("id", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.lastName").value("Lovelace"))
                .andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(get("/api/v1/users/me").param("id", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(actor.toString()));
        mvc.perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/users/me")).andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(profiles).getOwn(actor);
        verify(profiles).getOwnHeader(actor);
        verify(profiles).updateOwn(actor, new UpdateProfileRequest("Ada", "Lovelace"));
        verify(profiles).deleteOwn(actor);
        verifyNoMoreInteractions(profiles);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"firstName\":\"Ada\"}",
            "{\"firstName\":\" \",\"lastName\":\"Lovelace\"}",
            "{\"firstName\":\"Ada\",\"lastName\":\" \"}"
    })
    void rejectsIncompleteAndInvalidProfileWithoutEchoingValues(String body) throws Exception {
        mvc.perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors").isMap())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private-invalid-value"))));
        verifyNoInteractions(profiles);
    }

    @Test
    void rejectsEmailInProfilePatchAsUnknownProperty() throws Exception {
        mvc.perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\",\"email\":\"private@example.test\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(profiles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"roles", "id", "enabled", "emailVerified"})
    void rejectsPrivilegeAndIdentityFields(String field) throws Exception {
        String body = "{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\",\""
                + field + "\":null}";
        mvc.perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The request is invalid or unsupported."));
        verifyNoInteractions(profiles);
    }

    @Test
    void adminReadAndStatusHaveExplicitContracts() throws Exception {
        UUID target = UUID.randomUUID();
        when(profiles.list("ada", false, 2, 10))
                .thenReturn(new UserPageResponse(List.of(response(target)), 2, 10, 21, 3));
        when(profiles.getById(target)).thenReturn(response(target));
        when(profiles.setEnabled(actor, target, false)).thenReturn(response(target));
        mvc.perform(get("/api/v1/admin/users").param("search", "ada").param("enabled", "false")
                        .param("page", "2").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(target.toString()))
                .andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.totalElements").value(21));
        mvc.perform(get("/api/v1/admin/users/{id}", target)).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/admin/users/{id}/status", target)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        verify(profiles).list("ada", false, 2, 10);
        verify(profiles).getById(target);
        verify(profiles).setEnabled(actor, target, false);
    }

    @Test
    void defaultsPaginationAndRejectsOversizedSearch() throws Exception {
        when(profiles.list(null, null, 0, 20)).thenReturn(new UserPageResponse(List.of(), 0, 20, 0, 0));
        mvc.perform(get("/api/v1/admin/users")).andExpect(status().isOk());
        verify(profiles).list(null, null, 0, 20);
        clearInvocations(profiles);
        mvc.perform(get("/api/v1/admin/users").param("search", "x".repeat(256)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.search").exists());
        verifyNoInteractions(profiles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "enabled=invalid", "page=invalid"})
    void rejectsInvalidQueryParameters(String query) throws Exception {
        String[] parts = query.split("=", 2);
        mvc.perform(get("/api/v1/admin/users").param(parts[0], parts[1]))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(profiles);
    }

    @Test
    void rejectsInvalidUuidMalformedJsonAndMissingStatus() throws Exception {
        mvc.perform(get("/api/v1/admin/users/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/admin/users/{id}/status", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.enabled").exists());
        verifyNoInteractions(profiles);
    }

    @Test
    void mapsDomainFailuresToSanitizedProblems() throws Exception {
        when(profiles.getOwn(actor)).thenThrow(new UserNotFoundException(),
                new ProfileAccessDeniedException(), new ProfileConflictException(),
                new IdentityAccessException("private remote body and credentials"),
                new IllegalStateException("private remote body and credentials"));
        for (int code : new int[] {404, 403, 409, 503, 500}) {
            mvc.perform(get("/api/v1/users/me")).andExpect(status().is(code))
                    .andExpect(jsonPath("$.status").value(code))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("private remote body"))));
        }
    }

    private static UserResponse response(UUID id) {
        return new UserResponse(id, "ada@example.test", "Ada", "Lovelace", true, true,
                Set.of(UserRole.user), Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"));
    }
}
