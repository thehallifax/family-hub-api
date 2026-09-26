package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class GoogleCalendarSyncIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    private String token;
    private String memberId;
    private String familyId;

    @BeforeEach
    void setUp() throws Exception {
        String username = "sync" + (System.nanoTime() % 10_000_000_000L);
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "%s",
                                    "password": "password123",
                                    "familyName": "Sync Family",
                                    "members": [
                                        { "name": "SyncMember", "color": "coral", "email": "sync@test.com" }
                                    ]
                                }
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        token = JsonPath.read(body, "$.data.token");
        memberId = JsonPath.read(body, "$.data.family.members[0].id");
        familyId = JsonPath.read(body, "$.data.family.id");
    }

    @Test
    void googleEventsAppearInGetCalendarEvents() throws Exception {
        // Insert a Google-sourced regular event directly into DB
        insertGoogleEvent("google-evt-1", "Google Meeting", "09:00", "10:00",
                "2025-06-15", false, null, null, null, false, null,
                "https://calendar.google.com/event?eid=1");

        // Insert a native event for comparison
        mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "Native Event",
                                    "startTime": "2:00 PM",
                                    "endTime": "3:00 PM",
                                    "date": "2025-06-15",
                                    "audienceType": "MEMBERS", "memberIds": ["%s"],
                                    "isAllDay": false
                                }
                                """.formatted(memberId)))
                .andExpect(status().isCreated());

        // GET /calendar/events should return both
        mockMvc.perform(get("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-06-01")
                        .param("endDate", "2025-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                // First event (sorted by date then time): Google at 9 AM
                .andExpect(jsonPath("$.data[0].title").value("Google Meeting"))
                .andExpect(jsonPath("$.data[0].source").value("GOOGLE"))
                .andExpect(jsonPath("$.data[0].htmlLink").value("https://calendar.google.com/event?eid=1"))
                // Second event: Native at 2 PM
                .andExpect(jsonPath("$.data[1].title").value("Native Event"))
                .andExpect(jsonPath("$.data[1].source").value("NATIVE"))
                .andExpect(jsonPath("$.data[1].htmlLink").doesNotExist());
    }

    @Test
    void googleRecurringParentsAreExpanded() throws Exception {
        // Insert a Google recurring parent with RRULE
        insertGoogleEvent("google-recurring-1", "Weekly Standup", "09:00", "09:30",
                "2025-06-03", false, null, "FREQ=WEEKLY;BYDAY=TU", null, false, null, null);

        // GET events for June 2025 — should see expanded instances on Tuesdays
        mockMvc.perform(get("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-06-01")
                        .param("endDate", "2025-06-30"))
                .andExpect(status().isOk())
                // June 2025 Tuesdays: 3, 10, 17, 24
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].date").value("2025-06-03"))
                .andExpect(jsonPath("$.data[0].title").value("Weekly Standup"))
                .andExpect(jsonPath("$.data[0].source").value("GOOGLE"))
                .andExpect(jsonPath("$.data[0].isRecurring").value(true))
                .andExpect(jsonPath("$.data[1].date").value("2025-06-10"))
                .andExpect(jsonPath("$.data[2].date").value("2025-06-17"))
                .andExpect(jsonPath("$.data[3].date").value("2025-06-24"));
    }

    @Test
    void googleExceptionsReplaceExpandedInstances() throws Exception {
        // Insert parent
        String parentId = insertGoogleEvent("google-recurring-2", "Daily Sync", "10:00", "10:30",
                "2025-06-02", false, null, "FREQ=DAILY", null, false, null, null);

        // Insert edited exception for June 5 (rescheduled to 11:00)
        insertGoogleException("google-recurring-2_20250605", "Rescheduled Sync", "11:00", "11:30",
                "2025-06-05", parentId, "2025-06-05", false);

        // Insert cancelled exception for June 6
        insertGoogleException("google-recurring-2_20250606", "Daily Sync", "10:00", "10:30",
                "2025-06-06", parentId, "2025-06-06", true);

        // GET events for June 2-7
        mockMvc.perform(get("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-06-02")
                        .param("endDate", "2025-06-07"))
                .andExpect(status().isOk())
                // 6 days minus 1 cancelled = 5 events
                // Jun 2, 3, 4 (virtual), 5 (edited exception), 7 (virtual)
                // Jun 6 is cancelled — skipped
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].date").value("2025-06-02"))
                .andExpect(jsonPath("$.data[1].date").value("2025-06-03"))
                .andExpect(jsonPath("$.data[2].date").value("2025-06-04"))
                .andExpect(jsonPath("$.data[3].date").value("2025-06-05"))
                .andExpect(jsonPath("$.data[3].title").value("Rescheduled Sync"))
                .andExpect(jsonPath("$.data[3].startTime").value("11:00 AM"))
                .andExpect(jsonPath("$.data[4].date").value("2025-06-07"));
    }

    @Test
    void sourceFieldCorrectOnAllEvents() throws Exception {
        // Google event
        insertGoogleEvent("google-src-1", "From Google", "09:00", "10:00",
                "2025-06-15", false, null, null, null, false, null, null);

        // Native event
        mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "From App",
                                    "startTime": "2:00 PM",
                                    "endTime": "3:00 PM",
                                    "date": "2025-06-15",
                                    "audienceType": "MEMBERS", "memberIds": ["%s"],
                                    "isAllDay": false
                                }
                                """.formatted(memberId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-06-15")
                        .param("endDate", "2025-06-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("GOOGLE"))
                .andExpect(jsonPath("$.data[1].source").value("NATIVE"));
    }

    @Test
    void disconnectCleansUpGoogleEventsAndSyncedCalendars() throws Exception {
        // Insert a synced calendar row
        String syncedCalId = UUID.randomUUID().toString();
        String tokenId = insertOAuthToken();
        insertSyncedCalendar(syncedCalId, tokenId, "primary");

        // Insert Google events associated with this calendar
        insertGoogleEvent("google-disconnect-1", "Meeting", "09:00", "10:00",
                "2025-06-15", false, null, null, null, false, null, null);

        // Insert a native event — should survive disconnect
        mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "Native Survives",
                                    "startTime": "2:00 PM",
                                    "endTime": "3:00 PM",
                                    "date": "2025-06-15",
                                    "audienceType": "MEMBERS", "memberIds": ["%s"],
                                    "isAllDay": false
                                }
                                """.formatted(memberId)))
                .andExpect(status().isCreated());

        // Verify both events exist
        mockMvc.perform(get("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-06-15")
                        .param("endDate", "2025-06-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // Disconnect
        mockMvc.perform(delete("/api/google/disconnect/" + memberId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Verify Google events are gone but native event survives
        mockMvc.perform(get("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-06-15")
                        .param("endDate", "2025-06-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Native Survives"))
                .andExpect(jsonPath("$.data[0].source").value("NATIVE"));

        // Verify synced calendar rows are gone
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT count(*) FROM google_synced_calendar WHERE member_id = ?::uuid")) {
            stmt.setString(1, memberId);
            var rs = stmt.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }

        // Verify token row is gone
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT count(*) FROM google_oauth_token WHERE member_id = ?::uuid")) {
            stmt.setString(1, memberId);
            var rs = stmt.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void deletingParentCascadeDeletesExceptions() throws Exception {
        // Insert a recurring parent
        String parentId = insertGoogleEvent("cascade-parent", "Daily Sync", "10:00", "10:30",
                "2025-06-02", false, null, "FREQ=DAILY", null, false, null, null);

        // Insert two exceptions pointing to that parent
        insertGoogleException("cascade-parent_20250605", "Edited", "11:00", "11:30",
                "2025-06-05", parentId, "2025-06-05", false);
        insertGoogleException("cascade-parent_20250606", "Cancelled", "10:00", "10:30",
                "2025-06-06", parentId, "2025-06-06", true);

        // Verify all 3 rows exist
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT count(*) FROM calendar_event WHERE google_event_id LIKE 'cascade-parent%'")) {
            var rs = stmt.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(3);
        }

        // Delete parent (simulates incremental sync cancelling a parent)
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "DELETE FROM calendar_event WHERE google_event_id = 'cascade-parent'")) {
            stmt.executeUpdate();
        }

        // Verify exceptions are also gone via cascade
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT count(*) FROM calendar_event WHERE google_event_id LIKE 'cascade-parent%'")) {
            var rs = stmt.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void upsertingExistingEventUpdatesInPlace() throws Exception {
        // Insert a Google event
        insertGoogleEvent("upsert-evt-1", "Original Title", "09:00", "10:00",
                "2025-06-15", false, null, null, null, false, null, null);

        // Simulate an "upsert" by updating the same row (same google_event_id)
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "UPDATE calendar_event SET title = 'Updated Title' WHERE google_event_id = 'upsert-evt-1'")) {
            stmt.executeUpdate();
        }

        // Verify only one row exists and it has the new title
        mockMvc.perform(get("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-06-15")
                        .param("endDate", "2025-06-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Updated Title"));
    }

    // --- SQL Helpers ---

    private String insertGoogleEvent(String googleEventId, String title, String startTime,
                                      String endTime, String date, boolean isAllDay,
                                      String endDate, String recurrenceRule, String recurringEventId,
                                      boolean isCancelled, String originalDate, String htmlLink) throws Exception {
        String eventId = UUID.randomUUID().toString();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO calendar_event (id, title, start_time, end_time, date, source_owner_member_id, family_id, " +
                             "is_all_day, is_cancelled, source, google_event_id, html_link, end_date, recurrence_rule, " +
                             "recurring_event_id, original_date) " +
                             "VALUES (?::uuid, ?, ?::time, ?::time, ?::date, ?::uuid, ?::uuid, ?, ?, 'GOOGLE', ?, ?, " +
                             "?::date, ?, ?::uuid, ?::date)")) {
            stmt.setString(1, eventId);
            stmt.setString(2, title);
            stmt.setString(3, startTime);
            stmt.setString(4, endTime);
            stmt.setString(5, date);
            stmt.setString(6, memberId);
            stmt.setString(7, familyId);
            stmt.setBoolean(8, isAllDay);
            stmt.setBoolean(9, isCancelled);
            stmt.setString(10, googleEventId);
            stmt.setString(11, htmlLink);
            stmt.setString(12, endDate);
            stmt.setString(13, recurrenceRule);
            stmt.setString(14, recurringEventId);
            stmt.setString(15, originalDate);
            stmt.executeUpdate();
        }
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("INSERT INTO calendar_event_member (event_id, member_id) VALUES (?::uuid, ?::uuid)")) {
            stmt.setString(1, eventId);
            stmt.setString(2, memberId);
            stmt.executeUpdate();
        }
        return eventId;
    }

    private void insertGoogleException(String googleEventId, String title, String startTime,
                                        String endTime, String date, String parentId,
                                        String originalDate, boolean isCancelled) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO calendar_event (id, title, start_time, end_time, date, source_owner_member_id, family_id, " +
                             "is_all_day, is_cancelled, source, google_event_id, recurring_event_id, original_date) " +
                             "VALUES (gen_random_uuid(), ?, ?::time, ?::time, ?::date, ?::uuid, ?::uuid, false, ?, 'GOOGLE', ?, ?::uuid, ?::date)")) {
            stmt.setString(1, title);
            stmt.setString(2, startTime);
            stmt.setString(3, endTime);
            stmt.setString(4, date);
            stmt.setString(5, memberId);
            stmt.setString(6, familyId);
            stmt.setBoolean(7, isCancelled);
            stmt.setString(8, googleEventId);
            stmt.setString(9, parentId);
            stmt.setString(10, originalDate);
            stmt.executeUpdate();
        }
    }

    private String insertOAuthToken() throws Exception {
        String tokenId = UUID.randomUUID().toString();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO google_oauth_token (id, member_id, access_token, refresh_token, " +
                             "token_expiry, scope, created_at, updated_at) " +
                             "VALUES (?::uuid, ?::uuid, 'enc-access', 'enc-refresh', " +
                             "NOW() + INTERVAL '1 hour', 'calendar.events.readonly', NOW(), NOW())")) {
            stmt.setString(1, tokenId);
            stmt.setString(2, memberId);
            stmt.executeUpdate();
        }
        return tokenId;
    }

    private void insertSyncedCalendar(String id, String tokenId, String googleCalendarId) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO google_synced_calendar (id, token_id, member_id, google_calendar_id, " +
                             "calendar_name, enabled, created_at) " +
                             "VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'Primary', true, NOW())")) {
            stmt.setString(1, id);
            stmt.setString(2, tokenId);
            stmt.setString(3, memberId);
            stmt.setString(4, googleCalendarId);
            stmt.executeUpdate();
        }
    }
}
