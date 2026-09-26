package com.familyhub.demo.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarAudienceMigrationTest {
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll static void start() { postgres.start(); }
    @AfterAll static void stop() { postgres.stop(); }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).createSchemas(true).target(target).load();
    }

    @Test
    void v20EventsKeepTheirAssignmentsAndGoogleProvenanceThroughV21() throws Exception {
        String schema = "audience_" + UUID.randomUUID().toString().replace("-", "");
        flyway(schema, "20").migrate();
        UUID[] families = {UUID.randomUUID(), UUID.randomUUID()};
        UUID[] members = {UUID.randomUUID(), UUID.randomUUID()};
        UUID[] calendars = {UUID.randomUUID(), UUID.randomUUID()};
        UUID[] events = new UUID[5];
        for (int i = 0; i < events.length; i++) events[i] = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
            for (int i = 0; i < 2; i++) {
                statement.execute("INSERT INTO family (id,name,username,password_hash) VALUES ('" + families[i] + "','Family','" + families[i] + "','hash')");
                statement.execute("INSERT INTO family_member (id,family_id,name,color) VALUES ('" + members[i] + "','" + families[i] + "','Member','CORAL')");
                UUID token = UUID.randomUUID();
                statement.execute("INSERT INTO google_oauth_token (id,member_id,access_token,refresh_token,token_expiry,scope) VALUES ('" + token + "','" + members[i] + "','encrypted','encrypted',now(),'scope')");
                statement.execute("INSERT INTO google_synced_calendar (id,token_id,member_id,google_calendar_id) VALUES ('" + calendars[i] + "','" + token + "','" + members[i] + "','" + calendars[i] + "')");
            }
            insert(statement, events[0], members[0], families[0], null, null, null, null);
            insert(statement, events[1], members[0], families[0], null, "FREQ=WEEKLY;BYDAY=SU", null, null);
            insert(statement, events[2], members[0], families[0], null, null, events[1], "2025-06-15");
            insert(statement, events[3], members[0], families[0], calendars[0], null, null, null);
            insert(statement, events[4], members[1], families[1], calendars[1], null, null, null);
        }

        flyway(schema, "21").migrate();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
            var rows = statement.executeQuery("SELECT e.id,e.family_id,e.audience_type,e.source_owner_member_id,em.member_id,e.recurring_event_id " +
                    "FROM calendar_event e JOIN calendar_event_member em ON em.event_id=e.id");
            int count = 0;
            while (rows.next()) {
                UUID id = rows.getObject(1, UUID.class);
                boolean secondFamily = id.equals(events[4]);
                assertThat(rows.getObject(2, UUID.class)).isEqualTo(families[secondFamily ? 1 : 0]);
                assertThat(rows.getString(3)).isEqualTo("MEMBERS");
                assertThat(rows.getObject(5, UUID.class)).isEqualTo(members[secondFamily ? 1 : 0]);
                assertThat(rows.getObject(4, UUID.class)).isEqualTo(id.equals(events[3]) ? members[0] : secondFamily ? members[1] : null);
                if (id.equals(events[2])) assertThat(rows.getObject(6, UUID.class)).isEqualTo(events[1]);
                count++;
            }
            assertThat(count).isEqualTo(5);
        }
    }

    private void insert(java.sql.Statement statement, UUID id, UUID member, UUID family,
                        UUID calendar, String recurrence, UUID parent, String originalDate) throws Exception {
        String source = calendar == null ? "NATIVE" : "GOOGLE";
        String googleId = calendar == null ? "NULL" : "'same-google-id'";
        statement.execute("INSERT INTO calendar_event (id,title,start_time,end_time,date,member_id,family_id," +
                "source,synced_calendar_id,google_event_id,recurrence_rule,recurring_event_id,original_date) VALUES ('" +
                id + "','Existing','09:00','10:00','2025-06-15','" + member + "','" + family + "','" + source + "'," +
                uuid(calendar) + "," + googleId + "," + text(recurrence) + "," + uuid(parent) + "," + text(originalDate) + ")");
    }

    private String uuid(UUID value) { return value == null ? "NULL" : "'" + value + "'"; }
    private String text(String value) { return value == null ? "NULL" : "'" + value + "'"; }
}
