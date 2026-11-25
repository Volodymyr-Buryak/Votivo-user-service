package com.votivo.user_service;

import com.votivo.user_service.dto.keycloak.RemoteUser;
import com.votivo.user_service.entity.UserRole;
import com.votivo.user_service.repository.UserProfileRepository;
import com.votivo.user_service.service.ProfileWriter;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user_service_context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "spring.rabbitmq.dynamic=false",
        "app.identity.sync-enabled=false",
        "app.security.internal-secret=context-gateway-secret"
})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class UserServiceApplicationTests {

    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
	@Autowired ProfileWriter writer;
	@Autowired UserProfileRepository repository;
	@Autowired PlatformTransactionManager transactionManager;

    @Test
    void deletionMarkerIsDurableAndSynchronizationCannotRestorePersonalData() {
        UUID id = UUID.randomUUID();
        var source = RemoteUser.builder().id(id).email("deleted@example.test").enabled(true).emailVerified(true).build();
        writer.upsert(source, Set.of(UserRole.user));
        writer.requestDeletion(id);
        assertThat(writer.pendingIdentityDeletions()).contains(id);
        assertThat(repository.findById(id).orElseThrow().getDeletedAt()).isNotNull();
        writer.upsert(source, Set.of(UserRole.admin));
        var deleted = repository.findById(id).orElseThrow();
        assertThat(deleted.getDeletedAt()).isNotNull();
        assertThat(deleted.getEmail()).isNull();
        assertThat(deleted.isEnabled()).isFalse();
        assertThat(deleted.isIdentityDeletionPending()).isTrue();
        writer.completeIdentityDeletion(id);
        assertThat(writer.pendingIdentityDeletions()).doesNotContain(id);
    }

    @Test
    void administratorCannotBeSoftDeletedByTheDeletionWriter() {
        UUID id = UUID.randomUUID();
        writer.upsert(RemoteUser.builder().id(id).enabled(true).emailVerified(true).build(), Set.of(UserRole.admin));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.requestDeletion(id))
                .isInstanceOf(com.votivo.user_service.service.exception.ProfileAccessDeniedException.class);
        var admin = repository.findById(id).orElseThrow();
        assertThat(admin.getDeletedAt()).isNull();
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.isIdentityDeletionPending()).isFalse();
    }

	@Test
	void contextLoads() {
	}

    @Test
    void blockingAndUnblockingKeepProfileDataAndNeverDeleteTheUser() {
        UUID id = UUID.randomUUID();
        writer.upsert(RemoteUser.builder().id(id).email("kept@example.test").firstName("Alex")
                .enabled(true).emailVerified(true).build(), Set.of(UserRole.user));
        writer.setEnabled(id, false);
        var blocked = repository.findById(id).orElseThrow();
        assertThat(blocked.isEnabled()).isFalse();
        assertThat(blocked.getEmail()).isEqualTo("kept@example.test");
        Set<UserRole> roles = new TransactionTemplate(transactionManager).execute(status ->
                repository.findById(id).orElseThrow().getRoles());
        assertThat(roles).containsExactly(UserRole.user);
        assertThat(blocked.getSessionsRevokedAt()).isNotNull();
        assertThat(blocked.getDeletedAt()).isNull();
        writer.setEnabled(id, true);
        assertThat(repository.findById(id).orElseThrow().isEnabled()).isTrue();
        assertThat(repository.findById(id).orElseThrow().getSessionsRevokedAt()).isEqualTo(blocked.getSessionsRevokedAt());
    }

    @Test
    void gatewayAccessCheckAndProtectedApisFollowBlockingImmediately() throws Exception {
        UUID id = UUID.randomUUID();
        writer.upsert(RemoteUser.builder().id(id).email("status@example.test")
                .enabled(true).emailVerified(true).build(), Set.of(UserRole.user));
        String internal = "/internal/v1/users/" + id + "/access?issuedAt=" + Instant.now().getEpochSecond();
        var token = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(id.toString())).authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(internal))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(internal)
                .header("X-Internal-Auth", "context-gateway-secret"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        writer.setEnabled(id, false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(internal)
                .header("X-Internal-Auth", "context-gateway-secret"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me").with(token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Account-Access", "denied"));
        writer.setEnabled(id, true);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me").with(token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var fresh = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(id.toString()).issuedAt(Instant.now().plusSeconds(2))).authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me").with(fresh))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

	@Test
	void migratedSchemaPersistsRolesAndSoftDeletionAcrossTransactions() {
		UUID id = UUID.randomUUID();
		long created = 1_700_000_000_000L;
		writer.upsert(RemoteUser.builder().id(id).email("person@example.test").firstName("First").lastName("Last").emailVerified(true).enabled(true).createdTimestamp(created).build(), Set.of(UserRole.user, UserRole.admin));
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.executeWithoutResult(status -> {
			var profile = repository.findById(id).orElseThrow();
			assertThat(profile.getEmail()).isEqualTo("person@example.test");
			assertThat(profile.getFirstName()).isEqualTo("First");
			assertThat(profile.getLastName()).isEqualTo("Last");
			assertThat(profile.isEmailVerified()).isTrue();
			assertThat(profile.isEnabled()).isTrue();
			assertThat(profile.getRoles()).containsExactlyInAnyOrder(UserRole.user, UserRole.admin);
			assertThat(profile.getCreatedAt()).isEqualTo(Instant.ofEpochMilli(created));
			assertThat(profile.getUpdatedAt()).isNotNull();
			assertThat(profile.getSyncedAt()).isNotNull();
			assertThat(profile.getDeletedAt()).isNull();
		});
		assertThat(writer.activeIds()).contains(id);
		writer.softDelete(id);
		transaction.executeWithoutResult(status -> {
			var profile = repository.findById(id).orElseThrow();
			assertThat(profile.getDeletedAt()).isNotNull();
			assertThat(profile.isEnabled()).isFalse();
			assertThat(profile.isEmailVerified()).isFalse();
			assertThat(profile.getEmail()).isNull();
			assertThat(profile.getFirstName()).isNull();
			assertThat(profile.getLastName()).isNull();
			assertThat(profile.getRoles()).isEmpty();
		});
		assertThat(writer.activeIds()).doesNotContain(id);
		UUID withoutCreationTime = UUID.randomUUID();
		writer.upsert(RemoteUser.builder().id(withoutCreationTime).emailVerified(false).enabled(false).build(), Set.of());
		assertThat(repository.findById(withoutCreationTime).orElseThrow().getCreatedAt()).isNull();
	}
}
