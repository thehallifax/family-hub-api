package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.service.TokenEncryptionService;
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
import java.sql.ResultSet;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class GoogleCalendarSelectionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TokenEncryptionService encryptionService;

    private String token;
    private String memberId;
    private String tokenId;

    @BeforeEach
    void setUp() throws Exception {
        String username = "u" + (System.nanoTime() % 10_000_000_000L);
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "%s",
                                    "password": "password123",
                                    "familyName": "CalSel Family",
                                    "members": [
                                        { "name": "Member", "color": "teal", "email": "m@test.com" }
                                    ]
                                }
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        token = JsonPath.read(body, "$.data.token");
        memberId = JsonPath.read(body, "$.data.family.members[0].id");

        // Insert an OAuth token row
        String encryptedAccess = encryptionService.encrypt("fake-access-token");
        String encryptedRefresh = encryptionService.encrypt("fake-refresh-token");
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO google_oauth_token (id, member_id, access_token, refresh_token, token_expiry, scope) " +
                             "VALUES (gen_random_uuid(), ?::uuid, ?, ?, ?, 'calendar.events.readonly') RETURNING id")) {
            stmt.setString(1, memberId);
            stmt.setString(2, encryptedAccess);
            stmt.setString(3, encryptedRefresh);
            stmt.setObject(4, java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
            var rs = stmt.executeQuery();
            rs.next();
            tokenId = rs.getString("id");
        }
    }

    @Test
    void calendarSelectionLifecycle() throws Exception {
        // 1. Insert synced calendar rows directly (simulating a PUT without Google API)
        insertSyncedCalendar("primary", "Joe's Calendar", true);
        insertSyncedCalendar("work@group", "Work", false);

        // 2. Status should show calendars
        mockMvc.perform(get("/api/google/status/{memberId}", memberId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connected").value(true))
                .andExpect(jsonPath("$.data.calendars").isArray())
                .andExpect(jsonPath("$.data.calendars.length()").value(2));

        // 3. Update selection — disable work calendar
        disableSyncedCalendar("work@group");

        // 4. Status should reflect changes
        mockMvc.perform(get("/api/google/status/{memberId}", memberId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.calendars.length()").value(2));

        // 5. Verify enabled flags
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT google_calendar_id, enabled FROM google_synced_calendar WHERE member_id = ?::uuid ORDER BY google_calendar_id")) {
            stmt.setString(1, memberId);
            ResultSet rs = stmt.executeQuery();

            rs.next();
            assertThat(rs.getString("google_calendar_id")).isEqualTo("primary");
            assertThat(rs.getBoolean("enabled")).isTrue();

            rs.next();
            assertThat(rs.getString("google_calendar_id")).isEqualTo("work@group");
            assertThat(rs.getBoolean("enabled")).isFalse();
        }
    }

    @Test
    void disconnect_cleansUpSyncedCalendarsAndGoogleEvents() throws Exception {
        // 1. Insert synced calendar
        insertSyncedCalendar("primary", "Joe's Calendar", true);

        // 2. Insert a GOOGLE-sourced calendar event
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO calendar_event (id, title, start_time, end_time, date, source_owner_member_id, family_id, is_all_day, is_cancelled, source) " +
                             "SELECT gen_random_uuid(), 'Google Event', '09:00', '10:00', '2025-06-15', " +
                             "fm.id, fm.family_id, false, false, 'GOOGLE' FROM family_member fm WHERE fm.id = ?::uuid")) {
            stmt.setString(1, memberId);
            stmt.executeUpdate();
        }

        // 3. Verify rows exist
        assertThat(countSyncedCalendars()).isGreaterThan(0);
        assertThat(countGoogleEvents()).isGreaterThan(0);

        // 4. Disconnect
        mockMvc.perform(delete("/api/google/disconnect/{memberId}", memberId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 5. Verify all cleaned up
        assertThat(countSyncedCalendars()).isZero();
        assertThat(countGoogleEvents()).isZero();

        // 6. Status should show disconnected with empty calendars
        mockMvc.perform(get("/api/google/status/{memberId}", memberId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connected").value(false))
                .andExpect(jsonPath("$.data.calendars").isEmpty());
    }

    @Test
    void duplicateMemberAndCalendarId_violatesUniqueConstraint() throws Exception {
        insertSyncedCalendar("primary", "Joe's Calendar", true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                insertSyncedCalendar("primary", "Joe's Calendar", true)
        ).isInstanceOf(org.postgresql.util.PSQLException.class)
                .hasMessageContaining("uq_synced_calendar_member_google_id");
    }

    private void insertSyncedCalendar(String googleCalendarId, String name, boolean enabled) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO google_synced_calendar (id, token_id, member_id, google_calendar_id, calendar_name, enabled) " +
                             "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?, ?, ?)")) {
            stmt.setString(1, tokenId);
            stmt.setString(2, memberId);
            stmt.setString(3, googleCalendarId);
            stmt.setString(4, name);
            stmt.setBoolean(5, enabled);
            stmt.executeUpdate();
        }
    }

    private void disableSyncedCalendar(String googleCalendarId) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "UPDATE google_synced_calendar SET enabled = false WHERE member_id = ?::uuid AND google_calendar_id = ?")) {
            stmt.setString(1, memberId);
            stmt.setString(2, googleCalendarId);
            stmt.executeUpdate();
        }
    }

    private int countSyncedCalendars() throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM google_synced_calendar WHERE member_id = ?::uuid")) {
            stmt.setString(1, memberId);
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }

    private int countGoogleEvents() throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM calendar_event WHERE source_owner_member_id = ?::uuid AND source = 'GOOGLE'")) {
            stmt.setString(1, memberId);
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }
}
