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
        "app.identity.sync-enabled=false"
})
class UserServiceApplicationTests {
	@Autowired ProfileWriter writer;
	@Autowired UserProfileRepository repository;
	@Autowired PlatformTransactionManager transactionManager;

	@Test
	void contextLoads() {
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
