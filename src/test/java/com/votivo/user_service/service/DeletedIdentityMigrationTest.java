package com.votivo.user_service.service;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeletedIdentityMigrationTest {
    @Test
    void queuesOnlyPreviouslyDeletedAccountsAndRunsOnce() throws Exception {
        String url = "jdbc:h2:mem:deleted_identity_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("3").load().migrate();
        UUID deleted = UUID.randomUUID(), blocked = UUID.randomUUID(), active = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var insert = connection.prepareStatement("""
                     INSERT INTO user_profiles (id, email_verified, enabled, updated_at, synced_at, deleted_at, version)
                     VALUES (?, false, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 0)
                     """)) {
            for (UUID id : java.util.List.of(deleted, blocked, active)) {
                insert.setObject(1, id);
                insert.setBoolean(2, id.equals(active));
                insert.setObject(3, id.equals(deleted) ? java.time.OffsetDateTime.now() : null);
                insert.executeUpdate();
            }
        }
        Flyway latest = Flyway.configure().dataSource(url, "sa", "").load();
        latest.migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var query = connection.createStatement()) {
            try (var rows = query.executeQuery("SELECT id FROM user_profiles WHERE identity_deletion_pending = true")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1, UUID.class)).isEqualTo(deleted);
                assertThat(rows.next()).isFalse();
            }
            query.executeUpdate("UPDATE user_profiles SET identity_deletion_pending = false");
            latest.migrate();
            try (var rows = query.executeQuery("SELECT count(*) FROM user_profiles WHERE identity_deletion_pending = true")) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }
}
