package com.familyhub.demo.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleEventScopeMigrationTest {
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll static void start() { postgres.start(); }
    @AfterAll static void stop() { postgres.stop(); }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).createSchemas(true).target(target).load();
    }

    private Connection connect(String schema) throws SQLException {
        Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        connection.createStatement().execute("SET search_path TO \"" + schema + "\"");
        return connection;
    }

    private void insertEvent(Connection connection, UUID event, UUID family, UUID member,
                             UUID calendar, String googleId, String source) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO calendar_event
                (id,title,start_time,end_time,date,member_id,family_id,synced_calendar_id,google_event_id,source)
                VALUES (?,'Existing','09:00','10:00','2025-06-15',?,?,?,?,?)
                """)) {
            statement.setObject(1, event);
            statement.setObject(2, member);
            statement.setObject(3, family);
            statement.setObject(4, calendar);
            statement.setString(5, googleId);
            statement.setString(6, source);
            statement.executeUpdate();
        }
    }

    @Test
    void v17RowsSurviveAndV18AllowsSameGoogleIdInDifferentCalendars() throws SQLException {
        String schema = "google_scope_" + UUID.randomUUID().toString().replace("-", "");
        flyway(schema, "17").migrate();
        UUID family = UUID.randomUUID(), member = UUID.randomUUID(), token = UUID.randomUUID();
        UUID calendarA = UUID.randomUUID(), calendarB = UUID.randomUUID();
        UUID existingGoogle = UUID.randomUUID(), nativeEvent = UUID.randomUUID(), legacyGoogle = UUID.randomUUID();

        try (Connection connection = connect(schema); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO family (id,name,username,password_hash) VALUES ('" + family + "','Family','" + family + "','hash')");
            statement.execute("INSERT INTO family_member (id,family_id,name,color) VALUES ('" + member + "','" + family + "','Member','CORAL')");
            statement.execute("INSERT INTO google_oauth_token (id,member_id,access_token,refresh_token,token_expiry,scope) VALUES ('" + token + "','" + member + "','encrypted','encrypted',now(),'scope')");
            for (UUID calendar : new UUID[]{calendarA, calendarB}) {
                statement.execute("INSERT INTO google_synced_calendar (id,token_id,member_id,google_calendar_id) VALUES ('" + calendar + "','" + token + "','" + member + "','" + calendar + "')");
            }
            insertEvent(connection, existingGoogle, family, member, calendarA, "same-id", "GOOGLE");
            insertEvent(connection, nativeEvent, family, member, null, null, "NATIVE");
            insertEvent(connection, legacyGoogle, family, member, null, "legacy-id", "GOOGLE");
        }

        flyway(schema, "latest").migrate();
        try (Connection connection = connect(schema); var statement = connection.createStatement()) {
            var rows = statement.executeQuery("SELECT count(*) FROM calendar_event WHERE id IN ('" + existingGoogle + "','" + nativeEvent + "','" + legacyGoogle + "')");
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(3);
            rows.close();

            insertEvent(connection, UUID.randomUUID(), family, member, calendarB, "same-id", "GOOGLE");
            assertThatThrownBy(() -> insertEvent(connection, UUID.randomUUID(), family, member, calendarA, "same-id", "GOOGLE"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
