package com.votivo.user_service.service;

import com.votivo.user_service.dto.keycloak.RemoteUser;
import com.votivo.user_service.entity.UserRole;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:profile_reader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "spring.rabbitmq.dynamic=false",
        "app.identity.sync-enabled=false"
})
class ProfileReaderIntegrationTest {
    @Autowired ProfileWriter writer;
    @Autowired ProfileReader reader;

    @Test
    void searchesNamesAndEmailCaseInsensitivelyAndFiltersEnabledAndDeletedUsers() {
        String unique = UUID.randomUUID().toString();
        UUID active = UUID.randomUUID();
        UUID disabled = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        save(active, unique + "@example.org", "Alice", "First", true);
        save(disabled, "disabled@example.org", unique, "Second", false);
        save(deleted, "deleted@example.org", "Third", unique, true);
        writer.softDelete(deleted);

        var page = reader.list(unique.toUpperCase(), null, 0, 100);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).extracting(item -> item.id()).containsExactlyInAnyOrder(active, disabled);
        assertThat(page.items()).allSatisfy(item -> assertThat(item.roles()).containsExactly(UserRole.user));
        assertThat(reader.list(unique, true, 0, 100).items()).extracting(item -> item.id()).containsExactly(active);
        assertThat(reader.list(unique, false, 0, 100).items()).extracting(item -> item.id()).containsExactly(disabled);
        assertThat(reader.find(deleted)).isEmpty();
        assertThat(reader.exists(deleted)).isTrue();
        assertThat(reader.find(active)).get().satisfies(item -> assertThat(item.roles()).containsExactly(UserRole.user));
    }

    @Test
    void escapesWildcardsAndReturnsStableBoundedPages() {
        String unique = UUID.randomUUID().toString();
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        save(first, unique + "one@example.org", "Literal%_!", "A", true);
        save(second, unique + "two@example.org", "LiteralXX", "B", true);
        assertThat(reader.list("%_!", null, 0, 100).items()).extracting(item -> item.id()).containsExactly(first);
        var pageOne = reader.list(unique, null, 0, 1);
        var pageTwo = reader.list(unique, null, 1, 1);
        assertThat(pageOne.items()).extracting(item -> item.id()).containsExactly(first);
        assertThat(pageTwo.items()).extracting(item -> item.id()).containsExactly(second);
        assertThat(pageOne.page()).isZero();
        assertThat(pageTwo.page()).isEqualTo(1);
        assertThat(pageOne.size()).isEqualTo(1);
        assertThat(pageOne.totalElements()).isEqualTo(2);
        assertThat(pageOne.totalPages()).isEqualTo(2);
        assertThat(reader.list(unique, null, 2, 1).items()).isEmpty();
        assertThatThrownBy(() -> reader.list(unique, null, 0, 101))
                .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
    }

    private void save(UUID id, String email, String firstName, String lastName, boolean enabled) {
        writer.upsert(RemoteUser.builder().id(id).email(email).firstName(firstName).lastName(lastName)
                .emailVerified(true).enabled(enabled).createdTimestamp(1_700_000_000_000L).build(), Set.of(UserRole.user));
    }
}
