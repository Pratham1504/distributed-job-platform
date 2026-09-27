package io.jobplatform.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies that an installation with live V2 data upgrades safely to the current schema. */
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationUpgradeTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("migration_upgrade").withUsername("migration").withPassword("migration-password");

    @Test
    void upgradesExistingV2DataToTheCurrentFileAssetSchemaWithoutDataLoss() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target("2").load().migrate();

        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            insertExistingData(connection, userId, projectId, jobId, now);
        }

        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertEquals(1, count(connection, "select count(*) from users where id=?", userId));
            assertEquals(1, count(connection, "select count(*) from projects where id=?", projectId));
            assertEquals(1, count(connection, "select count(*) from jobs where id=?", jobId));
            assertEquals(3, count(connection, "select count(*) from flyway_schema_history where success"));
            try (ResultSet tables = connection.getMetaData().getTables(null, null, "file_assets", new String[] { "TABLE" })) {
                assertTrue(tables.next(), "V3 must create file_assets after preserving existing V2 data.");
            }
        }
    }

    private void insertExistingData(Connection connection, UUID userId, UUID projectId, UUID jobId, Instant now) throws Exception {
        try (PreparedStatement users = connection.prepareStatement(
                "insert into users (id,email,password_hash,role,created_at) values (?,?,?,?,?)")) {
            users.setObject(1, userId); users.setString(2, "upgrade@example.test"); users.setString(3, "hash");
            users.setString(4, "USER"); users.setTimestamp(5, Timestamp.from(now)); users.executeUpdate();
        }
        try (PreparedStatement projects = connection.prepareStatement(
                "insert into projects (id,owner_id,name,status,created_at) values (?,?,?,?,?)")) {
            projects.setObject(1, projectId); projects.setObject(2, userId); projects.setString(3, "Existing project");
            projects.setString(4, "ACTIVE"); projects.setTimestamp(5, Timestamp.from(now)); projects.executeUpdate();
        }
        try (PreparedStatement jobs = connection.prepareStatement("""
                insert into jobs (id,project_id,job_type,payload,payload_hash,status,priority,idempotency_key,created_at,updated_at)
                values (?,?,? ,cast(? as jsonb),?,?,?,?,?,?)
                """)) {
            jobs.setObject(1, jobId); jobs.setObject(2, projectId); jobs.setString(3, "GENERATE_REPORT");
            jobs.setString(4, "{}"); jobs.setString(5, "0".repeat(64)); jobs.setString(6, "COMPLETED");
            jobs.setString(7, "DEFAULT"); jobs.setString(8, "pre-upgrade-job"); jobs.setTimestamp(9, Timestamp.from(now));
            jobs.setTimestamp(10, Timestamp.from(now)); jobs.executeUpdate();
        }
    }

    private int count(Connection connection, String sql, UUID id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getInt(1); }
        }
    }

    private int count(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }
}
