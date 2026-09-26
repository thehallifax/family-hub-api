package com.familyhub.demo.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ChoreFortnightlyMigrationTest {
    @Test
    @SuppressWarnings("resource")
    void v19UpgradePreservesTemplatesAndCompletionsAndEnforcesFortnightSchedule() throws SQLException {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            String schema = "fortnight_" + UUID.randomUUID().toString().replace("-", "");
            flyway(postgres, schema, "19").migrate();
            UUID family = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID weekly = UUID.randomUUID();
            UUID monthly = UUID.randomUUID();
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("INSERT INTO family (id,name,username,password_hash) VALUES ('" + family + "','Test','test','hash')");
                statement.execute("INSERT INTO family_member (id,family_id,name,color) VALUES ('" + member + "','" + family + "','Pat','CORAL')");
                statement.execute("INSERT INTO chore_template (id,family_id,assigned_to_member_id,title,cadence,active_from,due_weekday) VALUES "
                        + "('" + weekly + "','" + family + "','" + member + "','Bins','WEEKLY','2026-01-01','SATURDAY'),"
                        + "('" + monthly + "','" + family + "','" + member + "','Sheets','MONTHLY','2026-01-01',NULL)");
                statement.execute("INSERT INTO chore_period_completion (chore_template_id,period_start_date,period_end_date,completed_at) VALUES "
                        + "('" + weekly + "','2026-05-17','2026-05-23','2026-05-18 12:00:00')");
            }
            flyway(postgres, schema, "latest").migrate();
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (var rows = statement.executeQuery("SELECT cadence,due_weekday,due_day_of_month,recurrence_anchor_date FROM chore_template ORDER BY cadence")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("cadence")).isEqualTo("MONTHLY");
                    assertThat(rows.getString("due_weekday")).isNull();
                    assertThat(rows.getObject("recurrence_anchor_date")).isNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("cadence")).isEqualTo("WEEKLY");
                    assertThat(rows.getString("due_weekday")).isEqualTo("SATURDAY");
                    assertThat(rows.getObject("recurrence_anchor_date")).isNull();
                    assertThat(rows.next()).isFalse();
                }
                try (var count = statement.executeQuery("SELECT count(*) FROM chore_period_completion WHERE chore_template_id='" + weekly + "'")) {
                    count.next();
                    assertThat(count.getInt(1)).isEqualTo(1);
                }
                UUID fortnight = UUID.randomUUID();
                statement.execute("INSERT INTO chore_template (id,family_id,assigned_to_member_id,title,cadence,active_from,due_weekday,recurrence_anchor_date) VALUES "
                        + "('" + fortnight + "','" + family + "','" + member + "','Recycling','FORTNIGHTLY','2026-01-01','SATURDAY','2026-10-03')");
                assertThatThrownBy(() -> statement.execute("UPDATE chore_template SET recurrence_anchor_date='2026-10-02' WHERE id='" + fortnight + "'"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    private Flyway flyway(PostgreSQLContainer<?> postgres, String schema, String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).createSchemas(true).target(target).load();
    }
}
