package com.familyhub.demo.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ChoreScheduleMigrationTest {
    @Test
    @SuppressWarnings("resource")
    void v18UpgradePreservesLegacyTemplatesAndCompletions() throws SQLException {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            String schema = "chore_" + UUID.randomUUID().toString().replace("-", "");
            flyway(postgres, schema, "18").migrate();
            UUID family = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID weekly = UUID.randomUUID();
            UUID monthly = UUID.randomUUID();
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("INSERT INTO family (id,name,username,password_hash) VALUES ('" + family + "','Test','test','hash')");
                statement.execute("INSERT INTO family_member (id,family_id,name,color) VALUES ('" + member + "','" + family + "','Pat','CORAL')");
                statement.execute("INSERT INTO chore_template (id,family_id,assigned_to_member_id,title,cadence,active_from) VALUES "
                        + "('" + weekly + "','" + family + "','" + member + "','Bins','WEEKLY','2026-01-01'),"
                        + "('" + monthly + "','" + family + "','" + member + "','Sheets','MONTHLY','2026-01-01')");
                statement.execute("INSERT INTO chore_period_completion (chore_template_id,period_start_date,period_end_date,completed_at) VALUES "
                        + "('" + weekly + "','2026-05-17','2026-05-23','2026-05-18 12:00:00')");
            }
            flyway(postgres, schema, "latest").migrate();
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (var rows = statement.executeQuery("SELECT cadence,due_weekday,due_day_of_month FROM chore_template ORDER BY cadence")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("due_weekday")).isNull();
                    assertThat(rows.getObject("due_day_of_month")).isNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("due_weekday")).isNull();
                    assertThat(rows.getObject("due_day_of_month")).isNull();
                    assertThat(rows.next()).isFalse();
                }
                try (var count = statement.executeQuery("SELECT count(*) FROM chore_period_completion WHERE chore_template_id='" + weekly + "'")) {
                    count.next();
                    assertThat(count.getInt(1)).isEqualTo(1);
                }
                statement.execute("UPDATE chore_template SET due_weekday='TUESDAY' WHERE id='" + weekly + "'");
                statement.execute("UPDATE chore_template SET due_day_of_month=31 WHERE id='" + monthly + "'");
                assertThatThrownBy(() -> statement.execute("UPDATE chore_template SET due_day_of_month=0 WHERE id='" + monthly + "'"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    private Flyway flyway(PostgreSQLContainer<?> postgres, String schema, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).createSchemas(true).target(target).load();
    }
}
