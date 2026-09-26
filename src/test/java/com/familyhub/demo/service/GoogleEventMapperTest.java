package com.familyhub.demo.service;

import com.familyhub.demo.model.*;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleEventMapperTest {

    private GoogleEventMapper mapper;
    private GoogleSyncedCalendar syncedCal;
    private FamilyMember member;
    private Family family;

    @BeforeEach
    void setUp() {
        mapper = new GoogleEventMapper();

        family = new Family();
        family.setId(UUID.randomUUID());

        member = new FamilyMember();
        member.setId(UUID.randomUUID());
        member.setFamily(family);

        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setMember(member);

        syncedCal = new GoogleSyncedCalendar();
        syncedCal.setMember(member);
        syncedCal.setToken(token);
        syncedCal.setGoogleCalendarId("primary");
    }

    @Nested
    class ToEntity {

        @Test
        void mapsRegularTimedEvent() {
            Event googleEvent = new Event();
            googleEvent.setId("google-event-123");
            googleEvent.setSummary("Team Meeting");
            googleEvent.setDescription("Weekly sync");
            googleEvent.setLocation("Conference Room A");
            googleEvent.setHtmlLink("https://calendar.google.com/event?eid=abc");
            googleEvent.setEtag("\"etag-value\"");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setStart(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-15T09:00:00-04:00")));
            googleEvent.setEnd(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-15T10:30:00-04:00")));

            CalendarEvent entity = mapper.toEntity(googleEvent, syncedCal);

            assertThat(entity.getTitle()).isEqualTo("Team Meeting");
            assertThat(entity.getDescription()).isEqualTo("Weekly sync");
            assertThat(entity.getLocation()).isEqualTo("Conference Room A");
            assertThat(entity.getHtmlLink()).isEqualTo("https://calendar.google.com/event?eid=abc");
            assertThat(entity.getGoogleEventId()).isEqualTo("google-event-123");
            assertThat(entity.getEtag()).isEqualTo("\"etag-value\"");
            assertThat(entity.getGoogleUpdatedAt()).isNotNull();
            assertThat(entity.getSource()).isEqualTo(EventSource.GOOGLE);
            assertThat(entity.getAudienceType()).isEqualTo(com.familyhub.demo.model.EventAudienceType.MEMBERS);
            assertThat(entity.getAudienceMembers()).containsExactly(member);
            assertThat(entity.getSourceOwnerMember()).isSameAs(member);
            assertThat(entity.getSyncedCalendar()).isSameAs(syncedCal);
            assertThat(entity.getFamily()).isEqualTo(family);
            assertThat(entity.isAllDay()).isFalse();
            assertThat(entity.getDate()).isEqualTo(LocalDate.of(2025, 6, 15));
            assertThat(entity.getStartTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(entity.getEndTime()).isEqualTo(LocalTime.of(10, 30));
            assertThat(entity.getRecurrenceRule()).isNull();
            assertThat(entity.getRecurringEvent()).isNull();
        }

        @Test
        void timedEventCrossingMidnightRetainsEndDate() {
            Event googleEvent = new Event().setId("overnight").setSummary("Night shift");
            googleEvent.setStart(new EventDateTime().setDateTime(new DateTime("2025-06-15T23:00:00+08:00")));
            googleEvent.setEnd(new EventDateTime().setDateTime(new DateTime("2025-06-16T01:00:00+08:00")));

            CalendarEvent entity = mapper.toEntity(googleEvent, syncedCal);

            assertThat(entity.getDate()).isEqualTo(LocalDate.of(2025, 6, 15));
            assertThat(entity.getEndDate()).isEqualTo(LocalDate.of(2025, 6, 16));
            assertThat(entity.getStartTime()).isEqualTo(LocalTime.of(23, 0));
            assertThat(entity.getEndTime()).isEqualTo(LocalTime.of(1, 0));
        }

        @Test
        void timedEventUsesOneZoneAcrossOffsetChange() {
            Event googleEvent = new Event().setId("dst").setSummary("Overnight");
            googleEvent.setStart(new EventDateTime()
                    .setDateTime(new DateTime("2025-03-08T23:00:00-05:00"))
                    .setTimeZone("America/New_York"));
            googleEvent.setEnd(new EventDateTime()
                    .setDateTime(new DateTime("2025-03-09T04:00:00-04:00")));

            CalendarEvent entity = mapper.toEntity(googleEvent, syncedCal);

            assertThat(entity.getDate()).isEqualTo(LocalDate.of(2025, 3, 8));
            assertThat(entity.getEndDate()).isEqualTo(LocalDate.of(2025, 3, 9));
            assertThat(entity.getEndTime()).isEqualTo(LocalTime.of(4, 0));
        }

        @Test
        void mapsAllDaySingleDayEvent() {
            Event googleEvent = new Event();
            googleEvent.setId("allday-123");
            googleEvent.setSummary("Birthday");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setStart(new EventDateTime()
                    .setDate(new DateTime("2025-06-15")));
            googleEvent.setEnd(new EventDateTime()
                    .setDate(new DateTime("2025-06-16")));

            CalendarEvent entity = mapper.toEntity(googleEvent, syncedCal);

            assertThat(entity.isAllDay()).isTrue();
            assertThat(entity.getDate()).isEqualTo(LocalDate.of(2025, 6, 15));
            assertThat(entity.getStartTime()).isEqualTo(LocalTime.MIDNIGHT);
            assertThat(entity.getEndTime()).isEqualTo(LocalTime.MIDNIGHT);
            assertThat(entity.getEndDate()).isNull();
        }

        @Test
        void mapsAllDayMultiDayEvent() {
            Event googleEvent = new Event();
            googleEvent.setId("multiday-123");
            googleEvent.setSummary("Vacation");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setStart(new EventDateTime()
                    .setDate(new DateTime("2025-06-15")));
            googleEvent.setEnd(new EventDateTime()
                    .setDate(new DateTime("2025-06-18")));

            CalendarEvent entity = mapper.toEntity(googleEvent, syncedCal);

            assertThat(entity.isAllDay()).isTrue();
            assertThat(entity.getDate()).isEqualTo(LocalDate.of(2025, 6, 15));
            assertThat(entity.getEndDate()).isEqualTo(LocalDate.of(2025, 6, 17));
        }

        @Test
        void mapsRecurringParentWithRrule() {
            Event googleEvent = new Event();
            googleEvent.setId("recurring-parent-123");
            googleEvent.setSummary("Weekly Standup");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setStart(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-03T09:00:00-04:00")));
            googleEvent.setEnd(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-03T09:30:00-04:00")));
            googleEvent.setRecurrence(List.of("RRULE:FREQ=WEEKLY;BYDAY=TU"));

            CalendarEvent entity = mapper.toEntity(googleEvent, syncedCal);

            assertThat(entity.getRecurrenceRule()).isEqualTo("FREQ=WEEKLY;BYDAY=TU");
            assertThat(entity.getRecurringEvent()).isNull();
        }

        @Test
        void handlesRecurrenceListWithNoRrule() {
            Event googleEvent = new Event();
            googleEvent.setId("exdate-only");
            googleEvent.setSummary("No RRULE");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setStart(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-03T09:00:00-04:00")));
            googleEvent.setEnd(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-03T09:30:00-04:00")));
            googleEvent.setRecurrence(List.of("EXDATE;VALUE=DATE:20250617"));

            CalendarEvent entity = mapper.toEntity(googleEvent, syncedCal);

            assertThat(entity.getRecurrenceRule()).isNull();
        }

        @Test
        void extractsExdatesFromRecurrenceList() {
            Event googleEvent = new Event();
            googleEvent.setId("exdate-test");
            googleEvent.setSummary("Weekly");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setStart(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-03T09:00:00-04:00")));
            googleEvent.setEnd(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-03T09:30:00-04:00")));
            googleEvent.setRecurrence(List.of(
                    "RRULE:FREQ=WEEKLY;BYDAY=TU",
                    "EXDATE;VALUE=DATE:20250617",
                    "EXDATE;VALUE=DATE:20250624"
            ));

            CalendarEvent entity = mapper.toEntity(googleEvent, syncedCal);

            assertThat(entity.getRecurrenceRule()).isEqualTo("FREQ=WEEKLY;BYDAY=TU");
            assertThat(entity.getExdates()).isEqualTo("2025-06-17,2025-06-24");
        }

        @Test
        void extractsExdatesFromTimedExdateFormat() {
            Event googleEvent = new Event();
            googleEvent.setId("exdate-timed");
            googleEvent.setSummary("Weekly");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setStart(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-03T09:00:00-04:00")));
            googleEvent.setEnd(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-03T09:30:00-04:00")));
            googleEvent.setRecurrence(List.of(
                    "RRULE:FREQ=WEEKLY;BYDAY=TU",
                    "EXDATE:20250617T130000Z"
            ));

            CalendarEvent entity = mapper.toEntity(googleEvent, syncedCal);

            assertThat(entity.getRecurrenceRule()).isEqualTo("FREQ=WEEKLY;BYDAY=TU");
            assertThat(entity.getExdates()).isEqualTo("2025-06-17");
        }

        @Test
        void noExdates_exdatesFieldIsNull() {
            Event googleEvent = new Event();
            googleEvent.setId("no-exdate");
            googleEvent.setSummary("Weekly");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setStart(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-03T09:00:00-04:00")));
            googleEvent.setEnd(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-03T09:30:00-04:00")));
            googleEvent.setRecurrence(List.of("RRULE:FREQ=WEEKLY;BYDAY=TU"));

            CalendarEvent entity = mapper.toEntity(googleEvent, syncedCal);

            assertThat(entity.getExdates()).isNull();
        }

        @Test
        void setsSyncedCalendarOnEntity() {
            Event googleEvent = new Event();
            googleEvent.setId("cal-assoc-1");
            googleEvent.setSummary("Test");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setStart(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-15T09:00:00-04:00")));
            googleEvent.setEnd(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-15T10:00:00-04:00")));

            CalendarEvent entity = mapper.toEntity(googleEvent, syncedCal);
            assertThat(entity.getSyncedCalendar()).isEqualTo(syncedCal);
        }

        @Test
        void handlesNullSummaryWithDefault() {
            Event googleEvent = new Event();
            googleEvent.setId("minimal-event");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setStart(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-15T09:00:00-04:00")));
            googleEvent.setEnd(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-15T10:00:00-04:00")));

            CalendarEvent entity = mapper.toEntity(googleEvent, syncedCal);

            assertThat(entity.getTitle()).isEqualTo("(No title)");
            assertThat(entity.getDescription()).isNull();
        }
    }

    @Nested
    class ToExceptionEntity {

        @Test
        void mapsEditedException() {
            CalendarEvent parentEntity = new CalendarEvent();
            parentEntity.setId(UUID.randomUUID());

            Event googleEvent = new Event();
            googleEvent.setId("recurring-parent-123_20250610T130000Z");
            googleEvent.setRecurringEventId("recurring-parent-123");
            googleEvent.setSummary("Rescheduled Standup");
            googleEvent.setStatus("confirmed");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setStart(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-10T10:00:00-04:00")));
            googleEvent.setEnd(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-10T10:30:00-04:00")));
            googleEvent.setOriginalStartTime(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-10T09:00:00-04:00")));

            CalendarEvent entity = mapper.toExceptionEntity(googleEvent, syncedCal, parentEntity);

            assertThat(entity.getTitle()).isEqualTo("Rescheduled Standup");
            assertThat(entity.getRecurringEvent()).isEqualTo(parentEntity);
            assertThat(entity.getOriginalDate()).isEqualTo(LocalDate.of(2025, 6, 10));
            assertThat(entity.isCancelled()).isFalse();
            assertThat(entity.getSource()).isEqualTo(EventSource.GOOGLE);
            assertThat(entity.getDate()).isEqualTo(LocalDate.of(2025, 6, 10));
            assertThat(entity.getStartTime()).isEqualTo(LocalTime.of(10, 0));
        }

        @Test
        void mapsCancelledExceptionWithoutStartEnd() {
            CalendarEvent parentEntity = new CalendarEvent();
            parentEntity.setId(UUID.randomUUID());
            parentEntity.setStartTime(LocalTime.of(9, 0));
            parentEntity.setEndTime(LocalTime.of(9, 30));
            parentEntity.setAllDay(false);

            Event googleEvent = new Event();
            googleEvent.setId("recurring-parent-123_20250617T130000Z");
            googleEvent.setRecurringEventId("recurring-parent-123");
            googleEvent.setStatus("cancelled");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setOriginalStartTime(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-17T09:00:00-04:00")));

            CalendarEvent entity = mapper.toExceptionEntity(googleEvent, syncedCal, parentEntity);

            assertThat(entity.isCancelled()).isTrue();
            assertThat(entity.getRecurringEvent()).isEqualTo(parentEntity);
            assertThat(entity.getOriginalDate()).isEqualTo(LocalDate.of(2025, 6, 17));
            assertThat(entity.getDate()).isEqualTo(LocalDate.of(2025, 6, 17));
            assertThat(entity.getStartTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(entity.getEndTime()).isEqualTo(LocalTime.of(9, 30));
        }

        @Test
        void setsSyncedCalendarOnException() {
            CalendarEvent parentEntity = new CalendarEvent();
            parentEntity.setId(UUID.randomUUID());
            parentEntity.setStartTime(LocalTime.of(9, 0));
            parentEntity.setEndTime(LocalTime.of(9, 30));
            parentEntity.setAllDay(false);

            Event googleEvent = new Event();
            googleEvent.setId("cal-assoc-exc");
            googleEvent.setRecurringEventId("parent");
            googleEvent.setSummary("Test Exception");
            googleEvent.setStatus("confirmed");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setStart(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-10T10:00:00-04:00")));
            googleEvent.setEnd(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-10T10:30:00-04:00")));
            googleEvent.setOriginalStartTime(new EventDateTime()
                    .setDateTime(new DateTime("2025-06-10T09:00:00-04:00")));

            CalendarEvent entity = mapper.toExceptionEntity(googleEvent, syncedCal, parentEntity);
            assertThat(entity.getSyncedCalendar()).isEqualTo(syncedCal);
        }

        @Test
        void mapsAllDayException() {
            CalendarEvent parentEntity = new CalendarEvent();
            parentEntity.setId(UUID.randomUUID());

            Event googleEvent = new Event();
            googleEvent.setId("allday-parent_20250615");
            googleEvent.setRecurringEventId("allday-parent");
            googleEvent.setSummary("Modified Holiday");
            googleEvent.setStatus("confirmed");
            googleEvent.setUpdated(new DateTime(1700000000000L));
            googleEvent.setStart(new EventDateTime()
                    .setDate(new DateTime("2025-06-15")));
            googleEvent.setEnd(new EventDateTime()
                    .setDate(new DateTime("2025-06-16")));
            googleEvent.setOriginalStartTime(new EventDateTime()
                    .setDate(new DateTime("2025-06-15")));

            CalendarEvent entity = mapper.toExceptionEntity(googleEvent, syncedCal, parentEntity);

            assertThat(entity.getOriginalDate()).isEqualTo(LocalDate.of(2025, 6, 15));
            assertThat(entity.isAllDay()).isTrue();
        }
    }
}
