package com.familyhub.demo.service;

import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.EventAudienceType;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.GoogleSyncedCalendarRepository;
import com.google.api.services.calendar.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleEventUpdatePersistenceServiceTest {
    @Mock GoogleSyncedCalendarRepository calendars;
    @Mock CalendarEventRepository events;
    @Mock GoogleEventMapper mapper;
    @InjectMocks GoogleEventUpdatePersistenceService service;

    Family family;
    FamilyMember owner;
    FamilyMember member;
    GoogleSyncedCalendar calendar;
    CalendarEvent parent;

    @BeforeEach
    void setup() {
        family = new Family();
        family.setId(UUID.randomUUID());
        owner = new FamilyMember();
        owner.setId(UUID.randomUUID());
        owner.setFamily(family);
        member = new FamilyMember();
        member.setId(UUID.randomUUID());
        member.setFamily(family);
        calendar = new GoogleSyncedCalendar();
        calendar.setId(UUID.randomUUID());
        calendar.setMember(owner);
        calendar.setEnabled(true);
        parent = event("parent", "google-parent");
        parent.setRecurrenceRule("RRULE:FREQ=WEEKLY;BYDAY=SU");
        parent.setAudienceType(EventAudienceType.MEMBERS);
        parent.getAudienceMembers().add(owner);
        when(calendars.findByIdForUpdate(calendar.getId())).thenReturn(Optional.of(calendar));
    }

    @Test
    void occurrenceEditCreatesOnlyAuthoritativeExceptionWithExplicitAudienceOverride() {
        Event google = new Event().setId("google-instance").setRecurringEventId("google-parent");
        CalendarEvent mapped = event("Moved occurrence", "google-instance");
        mapped.setRecurringEvent(parent);
        mapped.setOriginalDate(LocalDate.of(2026, 9, 27));
        when(events.findByFamilyAndId(family, parent.getId())).thenReturn(Optional.of(parent));
        when(events.findBySyncedCalendarAndSourceAndGoogleEventId(
                calendar, EventSource.GOOGLE, "google-instance")).thenReturn(Optional.empty());
        when(mapper.toExceptionEntity(google, calendar, parent)).thenReturn(mapped);
        when(events.saveAndFlush(mapped)).thenReturn(mapped);

        service.persistOccurrence(family, null, parent.getId(), calendar.getId(),
                "google-instance", google, EventAudienceType.MEMBERS, List.of(member));

        assertThat(mapped.isGoogleAudienceOverride()).isTrue();
        assertThat(mapped.getAudienceMembers()).containsExactly(member);
        assertThat(parent.getAudienceMembers()).containsExactly(owner);
        verify(events).saveAndFlush(mapped);
    }

    @Test
    void seriesAudienceFlowsToOrdinaryExceptionsButPreservesExplicitOverrides() {
        Event google = new Event().setId("google-parent");
        CalendarEvent mappedParent = event("Updated series", "google-parent");
        mappedParent.setRecurrenceRule("RRULE:FREQ=WEEKLY;BYDAY=SU");
        CalendarEvent inherited = event("Ordinary exception", "instance-1");
        inherited.setRecurringEvent(parent);
        inherited.getAudienceMembers().add(owner);
        CalendarEvent overridden = event("Explicit exception", "instance-2");
        overridden.setRecurringEvent(parent);
        overridden.setGoogleAudienceOverride(true);
        overridden.getAudienceMembers().add(owner);
        when(events.findByFamilyAndId(family, parent.getId())).thenReturn(Optional.of(parent));
        when(mapper.toEntity(google, calendar)).thenReturn(mappedParent);
        when(events.findByRecurringEvent(parent)).thenReturn(List.of(inherited, overridden));
        when(events.saveAndFlush(parent)).thenReturn(parent);

        service.persistSeries(family, parent.getId(), calendar.getId(), "google-parent",
                google, EventAudienceType.MEMBERS, List.of(member));

        assertThat(parent.getAudienceMembers()).containsExactly(member);
        assertThat(inherited.getAudienceMembers()).containsExactly(member);
        assertThat(inherited.isGoogleAudienceOverride()).isFalse();
        assertThat(overridden.getAudienceMembers()).containsExactly(owner);
        assertThat(overridden.isGoogleAudienceOverride()).isTrue();
        assertThat(inherited.getRecurringEvent()).isSameAs(parent);
        assertThat(overridden.getRecurringEvent()).isSameAs(parent);
    }

    @Test
    void deselectedCalendarCannotRestoreGoogleDataAfterRemoteWrite() {
        calendar.setEnabled(false);

        assertThatThrownBy(() -> service.persistSeries(family, parent.getId(), calendar.getId(),
                "google-parent", new Event().setId("google-parent"),
                EventAudienceType.FAMILY, List.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no longer selected");

        verify(events, never()).saveAndFlush(any());
    }

    private CalendarEvent event(String title, String googleId) {
        CalendarEvent event = new CalendarEvent();
        event.setId(UUID.randomUUID());
        event.setTitle(title);
        event.setStartTime(LocalTime.of(9, 0));
        event.setEndTime(LocalTime.of(10, 0));
        event.setDate(LocalDate.of(2026, 9, 27));
        event.setFamily(family);
        event.setSource(EventSource.GOOGLE);
        event.setSourceOwnerMember(owner);
        event.setSyncedCalendar(calendar);
        event.setGoogleEventId(googleId);
        return event;
    }
}
