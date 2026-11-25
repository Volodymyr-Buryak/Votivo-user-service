package com.votivo.user_service.controller;

import com.votivo.user_service.controller.advice.ApiExceptionHandler;
import com.votivo.user_service.dto.response.UserResponse;
import com.votivo.user_service.entity.UserRole;
import com.votivo.user_service.service.ProfileService;
import com.votivo.user_service.service.exception.ProfileAccessDeniedException;
import com.votivo.user_service.service.exception.UserNotFoundException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountAccessControllerTest {
    private final ProfileService profiles = mock(ProfileService.class);
    private final com.votivo.user_service.security.AccountStatus status = mock(com.votivo.user_service.security.AccountStatus.class);
    private final UUID id = UUID.randomUUID();
    private final String path = "/internal/v1/users/" + id + "/access?issuedAt=2000000000";
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AccountAccessController(profiles, status, "gateway-secret"))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test void requiresGatewaySecretBeforeReadingAnyAccount() throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Internal-Auth", "wrong")).andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }

    @Test void activeAccountIsAllowedAndAdminAccessRequiresCurrentRole() throws Exception {
        when(status.allows(eq(id), eq(false), any())).thenReturn(true);
        when(profiles.getOwn(id)).thenReturn(new UserResponse(id, "user@example.test", "Alex", "Morgan",
                true, true, Set.of(UserRole.user), Instant.EPOCH, Instant.EPOCH));
        mvc.perform(get(path).header("X-Internal-Auth", "gateway-secret"))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(path).param("admin", "true").header("X-Internal-Auth", "gateway-secret"))
                .andExpect(status().isForbidden());
    }

    @Test void blockedAndDeletedAccountsCannotPass() throws Exception {
        when(profiles.getOwn(id)).thenThrow(new ProfileAccessDeniedException());
        mvc.perform(get(path).header("X-Internal-Auth", "gateway-secret"))
                .andExpect(status().isForbidden());
        doThrow(new UserNotFoundException()).when(profiles).getOwn(id);
        mvc.perform(get(path).header("X-Internal-Auth", "gateway-secret"))
                .andExpect(status().isNotFound());
    }

    @Test void missingConfigurationFailsClosed() throws Exception {
        var unconfigured = MockMvcBuilders.standaloneSetup(new AccountAccessController(profiles, status, "")).build();
        unconfigured.perform(get(path).header("X-Internal-Auth", "gateway-secret"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(profiles);
    }
}
