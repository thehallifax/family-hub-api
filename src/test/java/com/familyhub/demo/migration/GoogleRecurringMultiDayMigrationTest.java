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

class GoogleRecurringMultiDayMigrationTest {
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll static void start() { postgres.start(); }
    @AfterAll static void stop() { postgres.stop(); }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).createSchemas(true).target(target).load();
    }

    @Test
    void v23PreservesNativeRuleAndPermitsRealGoogleOccurrenceDuration() throws Exception {
        String schema = "google_span_" + UUID.randomUUID().toString().replace("-", "");
        UUID family = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        flyway(schema, "22").migrate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
            statement.execute("INSERT INTO family(id,name,username,password_hash) VALUES ('" + family + "','Existing','" + family + "','hash')");
            statement.execute("INSERT INTO family_member(id,family_id,name,color) VALUES ('" + member + "','" + family + "','Member','CORAL')");
            String base = "INSERT INTO calendar_event(id,title,start_time,end_time,date,end_date,family_id,source_owner_member_id,source,recurrence_rule) VALUES ('";
            String tail = "','Event','23:00','08:00','2025-06-15','2025-06-16','" + family + "','" + member + "','";
            assertThatThrownBy(() -> statement.execute(base + UUID.randomUUID() + tail + "GOOGLE','FREQ=DAILY')"))
                    .hasMessageContaining("chk_no_recurring_multiday");
        }

        assertThat(flyway(schema, "23").migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
            String prefix = "INSERT INTO calendar_event(id,title,start_time,end_time,date,end_date,family_id,source_owner_member_id,source,recurrence_rule) VALUES ('";
            String dates = "','Event','23:00','08:00','2025-06-15','2025-06-16','" + family + "','" + member + "','";
            statement.execute(prefix + UUID.randomUUID() + dates + "GOOGLE','FREQ=DAILY')");
            assertThatThrownBy(() -> statement.execute(prefix + UUID.randomUUID() + dates + "NATIVE','FREQ=DAILY')"))
                    .hasMessageContaining("chk_no_recurring_multiday");
            String backwards = "','Event','09:00','10:00','2025-06-15','2025-06-14','" + family + "','" + member + "','";
            assertThatThrownBy(() -> statement.execute(prefix + UUID.randomUUID() + backwards + "GOOGLE','FREQ=DAILY')"))
                    .hasMessageContaining("chk_no_recurring_multiday");
            try (var rows = statement.executeQuery("SELECT count(*) FROM calendar_event WHERE source='GOOGLE' AND recurrence_rule='FREQ=DAILY'")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }
}
