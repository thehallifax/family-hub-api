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

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class CalendarEventIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private javax.sql.DataSource dataSource;

    private String token;
    private String memberId;

    private String registerAndExtract(String username) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "%s",
                                    "password": "password123",
                                    "familyName": "Event Family",
                                    "members": [
                                        { "name": "Member", "color": "teal", "email": "m@test.com" }
                                    ]
                                }
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @BeforeEach
    void setUp() throws Exception {
        String username = "user" + System.nanoTime();
        String body = registerAndExtract(username);
        token = JsonPath.read(body, "$.data.token");
        memberId = JsonPath.read(body, "$.data.family.members[0].id");
    }

    private String eventJson(String memberId) {
        return """
                {
                    "title": "Integration Event",
                    "startTime": "9:00 AM",
                    "endTime": "10:00 AM",
                    "date": "2025-06-15",
                    "audienceType": "MEMBERS", "memberIds": ["%s"],
                    "isAllDay": false,
                    "location": "Test Location"
                }
                """.formatted(memberId);
    }

    @Test
    void familyAndMultiMemberEventsRoundTripWithoutDuplicateRows() throws Exception {
        String familyBody = """
                {"title":"Zoo Lightscape","startTime":"9:00 AM","endTime":"10:00 AM",
                 "date":"2025-06-15","audienceType":"FAMILY","memberIds":[]}
                """;
        String familyResponse = mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(familyBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.audienceType").value("FAMILY"))
                .andExpect(jsonPath("$.data.memberIds").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String familyEventId = JsonPath.read(familyResponse, "$.data.id");

        String second = java.util.UUID.randomUUID().toString();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     INSERT INTO family_member (id,family_id,name,color)
                     SELECT ?::uuid,family_id,'Second','TEAL' FROM family_member WHERE id=?::uuid
                     """)) {
            stmt.setString(1, second);
            stmt.setString(2, memberId);
            stmt.executeUpdate();
        }
        String sharedBody = """
                {"title":"Dentist","startTime":"11:00 AM","endTime":"12:00 PM",
                 "date":"2025-06-15","audienceType":"MEMBERS","memberIds":["%s","%s"]}
                """.formatted(memberId, second);
        String sharedResponse = mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(sharedBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.audienceType").value("MEMBERS"))
                .andExpect(jsonPath("$.data.memberIds.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String sharedId = JsonPath.read(sharedResponse, "$.data.id");

        for (String person : new String[]{memberId, second}) {
            String listing = mockMvc.perform(get("/api/calendar/events")
                            .header("Authorization", "Bearer " + token)
                            .param("startDate", "2025-06-15").param("endDate", "2025-06-15")
                            .param("memberId", person))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            java.util.List<String> ids = JsonPath.read(listing, "$.data[*].id");
            assertThat(ids).containsExactlyInAnyOrder(familyEventId, sharedId);
        }
    }

    @Test
    void foreignFamilyAudienceCannotBeAssigned() throws Exception {
        String other = registerAndExtract("other" + System.nanoTime());
        String foreign = JsonPath.read(other, "$.data.family.members[0].id");
        mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(foreign)))
                .andExpect(status().isForbidden());
    }

    @Test
    void fullEventLifecycle() throws Exception {
        // Create
        String createBody = mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(memberId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("Integration Event"))
                .andExpect(jsonPath("$.data.memberId").value(memberId))
                .andReturn().getResponse().getContentAsString();

        String eventId = JsonPath.read(createBody, "$.data.id");

        // Query by date range — event should be present
        mockMvc.perform(get("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-06-01")
                        .param("endDate", "2025-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '%s')]".formatted(eventId)).exists());

        // Get by ID
        mockMvc.perform(get("/api/calendar/events/{id}", eventId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(eventId))
                .andExpect(jsonPath("$.data.title").value("Integration Event"))
                .andExpect(jsonPath("$.data.source").value("NATIVE"));

        // Delete
        mockMvc.perform(delete("/api/calendar/events/{id}", eventId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Verify gone
        mockMvc.perform(get("/api/calendar/events/{id}", eventId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    private String multiDayEventJson(String memberId) {
        return """
                {
                    "title": "Vacation",
                    "startTime": "12:00 AM",
                    "endTime": "12:00 AM",
                    "date": "2025-03-07",
                    "audienceType": "MEMBERS", "memberIds": ["%s"],
                    "isAllDay": true,
                    "endDate": "2025-03-09"
                }
                """.formatted(memberId);
    }

    @Test
    void multiDayEventLifecycle() throws Exception {
        // Create multi-day all-day event (Mar 7–9)
        String createBody = mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multiDayEventJson(memberId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("Vacation"))
                .andExpect(jsonPath("$.data.endDate").value("2025-03-09"))
                .andExpect(jsonPath("$.data.isAllDay").value(true))
                .andReturn().getResponse().getContentAsString();

        String eventId = JsonPath.read(createBody, "$.data.id");

        // Query middle day (Mar 8) — event should be found via overlap
        mockMvc.perform(get("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-03-08")
                        .param("endDate", "2025-03-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '%s')]".formatted(eventId)).exists());

        // Query outside range (Mar 10–15) — event should NOT be found
        mockMvc.perform(get("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-03-10")
                        .param("endDate", "2025-03-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '%s')]".formatted(eventId)).doesNotExist());
    }

    @Test
    void rejectEndDateWithoutAllDay() throws Exception {
        mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "Bad Event",
                                    "startTime": "9:00 AM",
                                    "endTime": "10:00 AM",
                                    "date": "2025-03-07",
                                    "audienceType": "MEMBERS", "memberIds": ["%s"],
                                    "isAllDay": false,
                                    "endDate": "2025-03-09"
                                }
                                """.formatted(memberId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectEndDateBeforeStartDate() throws Exception {
        mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "Bad Event",
                                    "startTime": "12:00 AM",
                                    "endTime": "12:00 AM",
                                    "date": "2025-03-09",
                                    "audienceType": "MEMBERS", "memberIds": ["%s"],
                                    "isAllDay": true,
                                    "endDate": "2025-03-07"
                                }
                                """.formatted(memberId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossFamilyAccess_denied() throws Exception {
        // Family A creates an event
        String createBody = mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(memberId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String eventId = JsonPath.read(createBody, "$.data.id");

        // Family B registers
        String familyBUsername = "user" + System.nanoTime();
        String familyBBody = registerAndExtract(familyBUsername);
        String familyBToken = JsonPath.read(familyBBody, "$.data.token");

        // Family B tries to access Family A's event — gets 404
        mockMvc.perform(get("/api/calendar/events/{id}", eventId)
                        .header("Authorization", "Bearer " + familyBToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void createEvent_hasSourceNative_andDescriptionRoundTrips() throws Exception {
        // Create with description
        String createBody = mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "Described Event",
                                    "startTime": "9:00 AM",
                                    "endTime": "10:00 AM",
                                    "date": "2025-06-15",
                                    "audienceType": "MEMBERS", "memberIds": ["%s"],
                                    "isAllDay": false,
                                    "description": "This is a test description"
                                }
                                """.formatted(memberId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.source").value("NATIVE"))
                .andExpect(jsonPath("$.data.description").value("This is a test description"))
                .andReturn().getResponse().getContentAsString();

        String eventId = JsonPath.read(createBody, "$.data.id");

        // GET returns source and description
        mockMvc.perform(get("/api/calendar/events/{id}", eventId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("NATIVE"))
                .andExpect(jsonPath("$.data.description").value("This is a test description"));

        // Update description
        mockMvc.perform(put("/api/calendar/events/{id}", eventId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "Described Event",
                                    "startTime": "9:00 AM",
                                    "endTime": "10:00 AM",
                                    "date": "2025-06-15",
                                    "audienceType": "MEMBERS", "memberIds": ["%s"],
                                    "isAllDay": false,
                                    "description": "Updated description"
                                }
                                """.formatted(memberId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value("Updated description"));
    }

    @Test
    void createEvent_withoutDescription_succeeds() throws Exception {
        mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(memberId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.source").value("NATIVE"))
                .andExpect(jsonPath("$.data.description").doesNotExist());
    }

    @Test
    void googleEvent_rejectsUpdateAndDelete() throws Exception {
        // Create a normal event
        String createBody = mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(memberId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String eventId = JsonPath.read(createBody, "$.data.id");

        // Directly update source to GOOGLE via SQL
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("UPDATE calendar_event SET source = 'GOOGLE', source_owner_member_id = ?::uuid WHERE id = ?::uuid")) {
            stmt.setString(1, memberId);
            stmt.setString(2, eventId);
            stmt.executeUpdate();
        }

        // PUT should be rejected
        mockMvc.perform(put("/api/calendar/events/{id}", eventId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(memberId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Google Calendar events cannot be modified in FamilyHub. Edit them in Google Calendar."));

        // DELETE should be rejected
        mockMvc.perform(delete("/api/calendar/events/{id}", eventId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Google Calendar events cannot be modified in FamilyHub. Edit them in Google Calendar."));
    }

    @Test
    void googleRecurringEvent_rejectsInstanceUpdateAndDelete() throws Exception {
        // Create a recurring event
        String createBody = mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recurringEventJson(memberId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String parentId = JsonPath.read(createBody, "$.data.id");

        // Mark as GOOGLE source
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("UPDATE calendar_event SET source = 'GOOGLE', source_owner_member_id = ?::uuid WHERE id = ?::uuid")) {
            stmt.setString(1, memberId);
            stmt.setString(2, parentId);
            stmt.executeUpdate();
        }

        // Edit instance — should be rejected
        String editJson = """
                {
                    "title": "Hacked Event",
                    "startTime": "9:00 AM",
                    "endTime": "10:00 AM",
                    "date": "2025-06-05",
                    "audienceType": "MEMBERS", "memberIds": ["%s"],
                    "isAllDay": false
                }
                """.formatted(memberId);

        mockMvc.perform(put("/api/calendar/events/{parentId}/instances/{date}", parentId, "2025-06-05")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Google Calendar events cannot be modified in FamilyHub. Edit them in Google Calendar."));

        // Delete instance — should be rejected
        mockMvc.perform(delete("/api/calendar/events/{parentId}/instances/{date}", parentId, "2025-06-05")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Google Calendar events cannot be modified in FamilyHub. Edit them in Google Calendar."));
    }

    private String recurringEventJson(String memberId) {
        return """
                {
                    "title": "Preschool",
                    "startTime": "9:00 AM",
                    "endTime": "12:00 PM",
                    "date": "2025-06-03",
                    "audienceType": "MEMBERS", "memberIds": ["%s"],
                    "isAllDay": false,
                    "location": "School",
                    "recurrenceRule": "FREQ=WEEKLY;BYDAY=TU,TH,FR"
                }
                """.formatted(memberId);
    }

    @Test
    void recurringEventLifecycle() throws Exception {
        // 1. Create recurring event
        String createBody = mockMvc.perform(post("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recurringEventJson(memberId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("Preschool"))
                .andExpect(jsonPath("$.data.recurrenceRule").value("FREQ=WEEKLY;BYDAY=TU,TH,FR"))
                .andExpect(jsonPath("$.data.isRecurring").value(true))
                .andReturn().getResponse().getContentAsString();

        String parentId = JsonPath.read(createBody, "$.data.id");

        // 2. GET expanded — query Jun 1–8 should return instances: Tue 3, Thu 5, Fri 6
        //    Virtual instances should have id=null and recurringEventId=parentId
        mockMvc.perform(get("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-06-01")
                        .param("endDate", "2025-06-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").doesNotExist())
                .andExpect(jsonPath("$.data[0].recurringEventId").value(parentId));

        // 3. Edit instance — change Thu Jun 5 title
        String editJson = """
                {
                    "title": "Preschool (Field Trip)",
                    "startTime": "9:00 AM",
                    "endTime": "1:00 PM",
                    "date": "2025-06-05",
                    "audienceType": "MEMBERS", "memberIds": ["%s"],
                    "isAllDay": false,
                    "location": "Zoo"
                }
                """.formatted(memberId);

        mockMvc.perform(put("/api/calendar/events/{parentId}/instances/{date}", parentId, "2025-06-05")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Preschool (Field Trip)"));

        // 4. GET confirms edit — Thu 5 should show edited title
        mockMvc.perform(get("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-06-01")
                        .param("endDate", "2025-06-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.date == '2025-06-05')].title")
                        .value("Preschool (Field Trip)"));

        // 5. Delete instance — remove Fri Jun 6
        mockMvc.perform(delete("/api/calendar/events/{parentId}/instances/{date}", parentId, "2025-06-06")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 6. GET confirms exclusion — should now return 2 instances
        mockMvc.perform(get("/api/calendar/events")
                        .header("Authorization", "Bearer " + token)
                        .param("startDate", "2025-06-01")
                        .param("endDate", "2025-06-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.date == '2025-06-06')]").doesNotExist());

        // 7. Delete parent — should cascade and clean up exceptions
        mockMvc.perform(delete("/api/calendar/events/{id}", parentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Verify parent is gone
        mockMvc.perform(get("/api/calendar/events/{id}", parentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
