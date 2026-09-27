package com.familyhub.demo.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChoreOneOffMigrationTest {
    @Test
    @SuppressWarnings("resource")
    void v24UpgradePreservesLegacySchedulesAndEnforcesOneOffFields() throws SQLException {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            String schema = "one_off_" + UUID.randomUUID().toString().replace("-", "");
            flyway(postgres, schema, "23").migrate();
            UUID family = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID legacyWeekly = UUID.randomUUID();
            UUID legacyMonthly = UUID.randomUUID();
            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("INSERT INTO family (id,name,username,password_hash) VALUES ('"
                        + family + "','Test','test','hash')");
                statement.execute("INSERT INTO family_member (id,family_id,name,color) VALUES ('"
                        + member + "','" + family + "','Pat','CORAL')");
                statement.execute("INSERT INTO chore_template "
                        + "(id,family_id,assigned_to_member_id,title,cadence,active_from) VALUES "
                        + "('" + legacyWeekly + "','" + family + "','" + member
                        + "','Weekly','WEEKLY','2026-01-01'),"
                        + "('" + legacyMonthly + "','" + family + "','" + member
                        + "','Monthly','MONTHLY','2026-01-01')");
            }

            flyway(postgres, schema, "latest").migrate();
            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (var rows = statement.executeQuery(
                        "SELECT count(*) FROM chore_template WHERE one_off_due_date IS NULL")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(2);
                }

                UUID oneOff = UUID.randomUUID();
                statement.execute("INSERT INTO chore_template "
                        + "(id,family_id,assigned_to_member_id,title,cadence,active_from,one_off_due_date) VALUES "
                        + "('" + oneOff + "','" + family + "','" + member
                        + "','Passport','ONE_OFF','2026-01-01','2026-06-01')");
                assertThatThrownBy(() -> statement.execute("UPDATE chore_template SET one_off_due_date=NULL WHERE id='"
                        + oneOff + "'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute("UPDATE chore_template SET due_weekday='MONDAY' WHERE id='"
                        + oneOff + "'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute("UPDATE chore_template SET cadence='DAILY' WHERE id='"
                        + oneOff + "'"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    private Flyway flyway(PostgreSQLContainer<?> postgres, String schema, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .target(target)
                .load();
    }
}
