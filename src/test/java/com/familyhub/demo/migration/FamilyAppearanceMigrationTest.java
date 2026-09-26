package com.familyhub.demo.migration;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class FamilyAppearanceMigrationTest {
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll static void start() { postgres.start(); }
    @AfterAll static void stop() { postgres.stop(); }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).createSchemas(true).target(target).load();
    }

    @Test void v21DataSurvivesAndAppearanceDefaultsWithoutBackfilling() throws Exception {
        String schema = "appearance_" + UUID.randomUUID().toString().replace("-", "");
        UUID family = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID chore = UUID.randomUUID();
        flyway(schema, "21").migrate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
            statement.execute("INSERT INTO family(id,name,username,password_hash) VALUES ('" + family + "','Existing','" + family + "','hash')");
            statement.execute("INSERT INTO family_member(id,family_id,name,color) VALUES ('" + member + "','" + family + "','Member','TEAL')");
            statement.execute("INSERT INTO calendar_event(id,title,start_time,end_time,date,family_id,audience_type) VALUES ('" + event + "','Existing','09:00','10:00','2026-09-26','" + family + "','MEMBERS')");
            statement.execute("INSERT INTO calendar_event_member(event_id,member_id) VALUES ('" + event + "','" + member + "')");
            statement.execute("INSERT INTO chore_template(id,family_id,assigned_to_member_id,title,cadence,active_from) VALUES ('" + chore + "','" + family + "','" + member + "','Existing','DAILY','2026-09-01')");
        }
        flyway(schema, "22").migrate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
            for (String table : new String[]{"family", "family_member", "calendar_event", "calendar_event_member", "chore_template"}) {
                try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM family_appearance")) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
            statement.execute("INSERT INTO family_appearance(family_id) VALUES ('" + family + "')");
            try (var rows = statement.executeQuery("SELECT accent,background_mode,gradient,background_strength FROM family_appearance")) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("PURPLE");
                assertThat(rows.getString(2)).isEqualTo("DEFAULT");
                assertThat(rows.getString(3)).isEqualTo("SUNRISE");
                assertThat(rows.getInt(4)).isEqualTo(55);
            }
        }
    }
}
