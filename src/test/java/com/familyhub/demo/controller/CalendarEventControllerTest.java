package com.familyhub.demo.controller;

import com.familyhub.demo.config.SecurityConfig;
import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.security.JwtAuthenticationEntryPoint;
import com.familyhub.demo.security.JwtAuthenticationFilter;
import com.familyhub.demo.security.WithMockFamily;
import com.familyhub.demo.service.CalendarEventService;
import com.familyhub.demo.service.FamilyService;
import com.familyhub.demo.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static com.familyhub.demo.TestDataFactory.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CalendarEventController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class})
@ActiveProfiles("test")
class CalendarEventControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    FamilyService familyService;

    @MockitoBean
    CalendarEventService calendarEventService;

    private CalendarEventResponse sampleEventResponse() {
        return CalendarEventResponse.builder()
                .id(EVENT_ID)
                .title("Test Event")
                .startTime("9:00 AM")
                .endTime("10:00 AM")
                .date(LocalDate.of(2025, 6, 15))
                .memberId(MEMBER_ID)
                .isAllDay(false)
                .location("Test Location")
                .endDate(null)
                .recurrenceRule(null)
                .recurringEventId(null)
                .isRecurring(false)
                .source("NATIVE")
                .description(null)
                .build();
    }

    private static final String EVENT_JSON = """
            {
                "title": "Test Event",
                "startTime": "9:00 AM",
                "endTime": "10:00 AM",
                "date": "2025-06-15",
                "audienceType": "MEMBERS",
                "memberIds": ["00000000-0000-0000-0000-000000000002"],
                "isAllDay": false,
                "location": "Test Location"
            }
            """;

    @Test
    @WithMockFamily
    void getEvents_returns200() throws Exception {
        given(calendarEventService.getAllEventsByFamily(any(Family.class), any(), any(), any()))
                .willReturn(List.of(sampleEventResponse()));

        mockMvc.perform(get("/api/calendar/events")
                        .param("startDate", "2025-06-01")
                        .param("endDate", "2025-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("Test Event"))
                .andExpect(jsonPath("$.data[0].id").value(EVENT_ID.toString()));
    }

    @Test
    @WithMockFamily
    void getEvents_withFilterParams_passesParamsToService() throws Exception {
        LocalDate start = LocalDate.of(2025, 6, 1);
        LocalDate end = LocalDate.of(2025, 6, 30);

        given(calendarEventService.getAllEventsByFamily(any(Family.class), eq(start), eq(end), eq(MEMBER_ID)))
                .willReturn(List.of(sampleEventResponse()));

        mockMvc.perform(get("/api/calendar/events")
                        .param("startDate", "2025-06-01")
                        .param("endDate", "2025-06-30")
                        .param("memberId", MEMBER_ID.toString()))
                .andExpect(status().isOk());

        verify(calendarEventService).getAllEventsByFamily(any(Family.class), eq(start), eq(end), eq(MEMBER_ID));
    }

    @Test
    @WithMockFamily
    void getEventById_returns200() throws Exception {
        given(calendarEventService.getEventById(eq(EVENT_ID), any(Family.class)))
                .willReturn(sampleEventResponse());

        mockMvc.perform(get("/api/calendar/events/{id}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Test Event"));
    }

    @Test
    @WithMockFamily
    void addEvent_returns201WithLocationHeader() throws Exception {
        given(calendarEventService.addCalendarEvent(any(), any(Family.class)))
                .willReturn(sampleEventResponse());

        mockMvc.perform(post("/api/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/calendar/events/" + EVENT_ID))
                .andExpect(jsonPath("$.data.title").value("Test Event"))
                .andExpect(jsonPath("$.message").value("Event created successfully"));
    }

    @Test
    @WithMockFamily
    void addEvent_emptyTitle_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "",
                                    "startTime": "9:00 AM",
                                    "endTime": "10:00 AM",
                                    "date": "2025-06-15",
                                    "memberId": "00000000-0000-0000-0000-000000000002"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'title')]").exists());
    }

    @Test
    @WithMockFamily
    void addEvent_nullDate_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "Test Event",
                                    "startTime": "9:00 AM",
                                    "endTime": "10:00 AM",
                                    "memberId": "00000000-0000-0000-0000-000000000002"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'date')]").exists());
    }

    @Test
    @WithMockFamily
    void addEvent_missingAudience_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "Test Event",
                                    "startTime": "9:00 AM",
                                    "endTime": "10:00 AM",
                                    "date": "2025-06-15"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'audienceType')]").exists());
    }

    @Test
    @WithMockFamily
    void updateEvent_returns200() throws Exception {
        given(calendarEventService.updateCalendarEvent(any(), eq(EVENT_ID), any(Family.class)))
                .willReturn(sampleEventResponse());

        mockMvc.perform(put("/api/calendar/events/{id}", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Test Event"))
                .andExpect(jsonPath("$.message").value("Event updated successfully"));
    }

    @Test
    @WithMockFamily
    void deleteEvent_returns204() throws Exception {
        willDoNothing().given(calendarEventService).deleteCalendarEvent(eq(EVENT_ID), any(Family.class));

        mockMvc.perform(delete("/api/calendar/events/{id}", EVENT_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockFamily
    void editRecurringInstance_returns200() throws Exception {
        given(calendarEventService.editRecurringInstance(eq(EVENT_ID), eq(LocalDate.of(2025, 6, 5)), any(), any(Family.class)))
                .willReturn(sampleEventResponse());

        mockMvc.perform(put("/api/calendar/events/{parentId}/instances/{date}", EVENT_ID, "2025-06-05")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Instance updated successfully"));
    }

    @Test
    @WithMockFamily
    void deleteRecurringInstance_returns204() throws Exception {
        willDoNothing().given(calendarEventService).deleteRecurringInstance(eq(EVENT_ID), eq(LocalDate.of(2025, 6, 5)), any(Family.class));

        mockMvc.perform(delete("/api/calendar/events/{parentId}/instances/{date}", EVENT_ID, "2025-06-05"))
                .andExpect(status().isNoContent());
    }

    @Test
    void getEvents_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/calendar/events"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.httpStatus").value(401));
    }
}
