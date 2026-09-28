package com.familyhub.demo.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleRecurringAudienceMigrationTest {
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll static void start() { postgres.start(); }
    @AfterAll static void stop() { postgres.stop(); }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).createSchemas(true).target(target).load();
    }

    @Test
    void v25DefaultsExistingRowsAndRestrictsOverridesToGoogleExceptions() throws Exception {
        String schema = "google_audience_" + UUID.randomUUID().toString().replace("-", "");
        UUID family = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        UUID existingException = UUID.randomUUID();
        flyway(schema, "24").migrate();
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
            statement.execute("INSERT INTO family(id,name,username,password_hash) VALUES ('" + family
                    + "','Existing','" + family + "','hash')");
            statement.execute("INSERT INTO family_member(id,family_id,name,color) VALUES ('" + member
                    + "','" + family + "','Member','CORAL')");
            statement.execute("INSERT INTO calendar_event(id,title,start_time,end_time,date,family_id,"
                    + "source_owner_member_id,source,recurrence_rule,google_event_id) VALUES ('" + parent
                    + "','Series','09:00','10:00','2026-09-20','" + family + "','" + member
                    + "','GOOGLE','FREQ=WEEKLY','google-parent')");
            statement.execute("INSERT INTO calendar_event(id,title,start_time,end_time,date,family_id,"
                    + "source_owner_member_id,source,recurring_event_id,original_date,google_event_id) VALUES ('"
                    + existingException + "','Exception','09:00','10:00','2026-09-27','" + family + "','"
                    + member + "','GOOGLE','" + parent + "','2026-09-27','google-instance')");
        }

        assertThat(flyway(schema, "25").migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
            try (var rows = statement.executeQuery("SELECT google_audience_override FROM calendar_event "
                    + "WHERE id='" + existingException + "'")) {
                rows.next();
                assertThat(rows.getBoolean(1)).isFalse();
            }
            statement.execute("UPDATE calendar_event SET google_audience_override=TRUE WHERE id='"
                    + existingException + "'");
            assertThatThrownBy(() -> statement.execute(
                    "UPDATE calendar_event SET google_audience_override=TRUE WHERE id='" + parent + "'"))
                    .hasMessageContaining("chk_google_audience_override");
            assertThatThrownBy(() -> statement.execute(
                    "UPDATE calendar_event SET source='NATIVE', google_audience_override=TRUE WHERE id='"
                            + existingException + "'"))
                    .hasMessageContaining("chk_google_audience_override");
        }
    }
}
