package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.dto.GoogleCalendarInfo;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ConflictException;
import com.familyhub.demo.exception.GoogleWriteUncertainException;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.EventAudienceType;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleEventUpdateServiceTest {
    @Mock CalendarEventRepository events;
    @Mock FamilyMemberRepository members;
    @Mock GoogleOAuthTokenRepository tokens;
    @Mock GoogleCalendarListService discovery;
    @Mock GoogleCredentialService credentials;
    @Mock GoogleEventUpdatePersistenceService persistence;
    @Mock GoogleEventDeletionPersistenceService deletionPersistence;
    @Spy @InjectMocks GoogleEventUpdateService service;

    Family family;
    FamilyMember member;
    GoogleOAuthToken token;
    GoogleSyncedCalendar calendar;
    CalendarEvent local;
    Calendar client;
    Calendar.Events api;
    Calendar.Events.Get get;

    @BeforeEach
    void setup() throws IOException {
        family = new Family(); family.setId(UUID.randomUUID()); family.setTimezone("Australia/Perth");
        member = new FamilyMember(); member.setId(UUID.randomUUID()); member.setFamily(family);
        token = new GoogleOAuthToken(); token.setId(UUID.randomUUID()); token.setMember(member);
        token.setScope(GoogleOAuthService.EVENT_WRITE_SCOPE);
        calendar = new GoogleSyncedCalendar(); calendar.setId(UUID.randomUUID()); calendar.setMember(member);
        calendar.setToken(token); calendar.setGoogleCalendarId("primary"); calendar.setEnabled(true);
        local = new CalendarEvent(); local.setId(UUID.randomUUID()); local.setFamily(family);
        local.setSource(EventSource.GOOGLE); local.setSourceOwnerMember(member); local.setSyncedCalendar(calendar);
        local.setGoogleEventId("google-1"); local.setEtag("etag-1");
        client = mock(Calendar.class); api = mock(Calendar.Events.class); get = mock(Calendar.Events.Get.class);
    }

    private CalendarEventRequest request(EventAudienceType audience, List<UUID> ids, boolean allDay,
                                         LocalDate endDate) {
        return new CalendarEventRequest("Updated", "11:00 PM", "8:00 AM", LocalDate.of(2026, 9, 27),
                audience, ids, allDay, "New place", endDate, null, "New notes");
    }

    private Event available(String role) throws IOException {
        when(events.findGoogleEventForWrite(family, local.getId())).thenReturn(Optional.of(local));
        when(tokens.findByMemberId(member.getId())).thenReturn(Optional.of(token));
        when(discovery.listCalendars(member.getId())).thenReturn(List.of(
                new GoogleCalendarInfo("primary", "Personal", true, role)));
        doReturn(client).when(service).buildClient(member.getId());
        when(client.events()).thenReturn(api);
        when(api.get("primary", "google-1")).thenReturn(get);
        Event current = new Event().setId("google-1").setEtag("etag-1")
                .setSummary("Old").setAttendees(List.of(new EventAttendee().setEmail("guest@example.com")))
                .setStart(new EventDateTime()).setEnd(new EventDateTime());
        when(get.execute()).thenReturn(current);
        return current;
    }

    @Test
    void updatesGoogleConditionallyBeforeExistingRowAndPreservesAttendeesAndMembersAudience() throws IOException {
        Event current = available("owner");
        when(members.findAllById(any())).thenReturn(List.of(member));
        Calendar.Events.Update update = mock(Calendar.Events.Update.class);
        HttpHeaders headers = mock(HttpHeaders.class);
        when(api.update("primary", "google-1", current)).thenReturn(update);
        when(update.getRequestHeaders()).thenReturn(headers);
        Event returned = new Event().setId("google-1").setEtag("etag-2");
        when(update.execute()).thenReturn(returned);

        service.update(family, local.getId(), request(EventAudienceType.MEMBERS,
                List.of(member.getId()), false, LocalDate.of(2026, 9, 28)));

        verify(headers).setIfMatch("etag-1");
        assertThat(current.getSummary()).isEqualTo("Updated");
        assertThat(current.getLocation()).isEqualTo("New place");
        assertThat(current.getDescription()).isEqualTo("New notes");
        assertThat(current.getStart().getDateTime().toStringRfc3339()).contains("2026-09-27T23:00:00.000+08:00");
        assertThat(current.getEnd().getDateTime().toStringRfc3339()).contains("2026-09-28T08:00:00.000+08:00");
        assertThat(current.getAttendees()).hasSize(1);
        var order = inOrder(update, persistence);
        order.verify(update).execute();
        order.verify(persistence).persist(family, local.getId(), calendar.getId(), "google-1",
                returned, EventAudienceType.MEMBERS, List.of(member));
    }

    @Test
    void mapsMultiDayAllDayAndFamilyAudienceWithoutAttendeesMutation() throws IOException {
        Event current = available("writer");
        Calendar.Events.Update update = mock(Calendar.Events.Update.class);
        when(api.update("primary", "google-1", current)).thenReturn(update);
        when(update.getRequestHeaders()).thenReturn(new HttpHeaders());
        when(update.execute()).thenReturn(new Event().setId("google-1").setEtag("etag-2"));

        service.update(family, local.getId(), request(EventAudienceType.FAMILY, List.of(), true,
                LocalDate.of(2026, 9, 29)));

        assertThat(current.getStart().getDate().toStringRfc3339()).isEqualTo("2026-09-27");
        assertThat(current.getEnd().getDate().toStringRfc3339()).isEqualTo("2026-09-30");
        assertThat(current.getAttendees()).hasSize(1);
        verify(persistence).persist(eq(family), eq(local.getId()), eq(calendar.getId()), eq("google-1"),
                any(Event.class), eq(EventAudienceType.FAMILY), eq(List.of()));
    }

    @Test
    void staleEtagRefreshesLocalGoogleContentAndReturnsConflictWithoutUpdate() throws IOException {
        Event current = available("owner").setEtag("etag-external");

        assertThatThrownBy(() -> service.update(family, local.getId(),
                request(EventAudienceType.FAMILY, List.of(), true, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("changed in Google");

        verify(persistence).persist(family, local.getId(), calendar.getId(), "google-1",
                current, null, List.of());
        verify(api, never()).update(anyString(), anyString(), any());
    }

    @Test
    void googleFailureKeepsLocalAndLocalFailureAfterGoogleIsUncertain() throws IOException {
        Event current = available("owner");
        Calendar.Events.Update update = mock(Calendar.Events.Update.class);
        when(api.update("primary", "google-1", current)).thenReturn(update);
        when(update.getRequestHeaders()).thenReturn(new HttpHeaders());
        when(update.execute()).thenThrow(new IOException("network"));
        assertThatThrownBy(() -> service.update(family, local.getId(),
                request(EventAudienceType.FAMILY, List.of(), true, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("kept");
        verifyNoInteractions(persistence);

        reset(update, persistence);
        when(update.getRequestHeaders()).thenReturn(new HttpHeaders());
        when(update.execute()).thenReturn(new Event().setId("google-1").setEtag("etag-2"));
        when(persistence.persist(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db"));
        assertThatThrownBy(() -> service.update(family, local.getId(),
                request(EventAudienceType.FAMILY, List.of(), true, null)))
                .isInstanceOf(GoogleWriteUncertainException.class).hasMessageContaining("Sync Now");
    }

    @Test
    void missingGoogleEventRemovesGhostAndReturnsNotFound() throws IOException {
        available("owner");
        GoogleJsonResponseException missing = mock(GoogleJsonResponseException.class);
        when(missing.getStatusCode()).thenReturn(404);
        when(get.execute()).thenThrow(missing);

        assertThatThrownBy(() -> service.update(family, local.getId(),
                request(EventAudienceType.FAMILY, List.of(), true, null)))
                .hasMessageContaining("Google Calendar Event");
        verify(deletionPersistence).reconcile(family, local.getId(), calendar.getId(), "google-1");
    }

    @Test
    void readOnlyMissingScopeForeignAndRecurringAreRejectedBeforeGoogleUpdate() {
        when(events.findGoogleEventForWrite(family, local.getId())).thenReturn(Optional.of(local));
        when(tokens.findByMemberId(member.getId())).thenReturn(Optional.of(token));
        when(discovery.listCalendars(member.getId())).thenReturn(List.of(
                new GoogleCalendarInfo("primary", "Personal", true, "reader")));
        assertThatThrownBy(() -> service.update(family, local.getId(),
                request(EventAudienceType.FAMILY, List.of(), true, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("read-only");
        token.setScope("https://www.googleapis.com/auth/calendar.events.readonly");
        assertThatThrownBy(() -> service.update(family, local.getId(),
                request(EventAudienceType.FAMILY, List.of(), true, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("no longer writable");
        local.setRecurrenceRule("FREQ=DAILY");
        assertThatThrownBy(() -> service.update(family, local.getId(),
                request(EventAudienceType.FAMILY, List.of(), true, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Recurring Google");
        Family other = new Family(); other.setId(UUID.randomUUID());
        when(events.findGoogleEventForWrite(other, local.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(other, local.getId(),
                request(EventAudienceType.FAMILY, List.of(), true, null)))
                .hasMessageContaining("Calendar Event");
        verify(service, never()).buildClient(any());
    }
}
