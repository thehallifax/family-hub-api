package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.dto.GoogleCalendarInfo;
import com.familyhub.demo.dto.GoogleEventEditScope;
import com.familyhub.demo.dto.GoogleEventUpdateRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ConflictException;
import com.familyhub.demo.exception.GoogleWriteUncertainException;
import com.familyhub.demo.exception.ResourceNotFoundException;
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
import com.google.api.client.util.DateTime;
import com.google.api.client.http.HttpHeaders;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;
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

    @Test
    void editsVirtualTimedOccurrenceUsingGoogleResolvedInstanceIdentity() throws IOException {
        local.setRecurrenceRule("FREQ=WEEKLY;BYDAY=SU");
        local.setStartTime(java.time.LocalTime.of(9, 0));
        available("owner");
        when(events.findGoogleEventForWrite(family, local.getId())).thenReturn(Optional.of(local));

        Event googleParent = new Event().setId("google-1").setEtag("parent-etag")
                .setStart(new EventDateTime()
                        .setDateTime(new DateTime("2026-09-20T09:00:00+08:00"))
                        .setTimeZone("Australia/Perth"))
                .setEnd(new EventDateTime().setDateTime(new DateTime("2026-09-20T10:00:00+08:00")))
                .setRecurrence(List.of("RRULE:FREQ=WEEKLY;BYDAY=SU"));
        when(get.execute()).thenReturn(googleParent);

        Calendar.Events.Instances instancesRequest = mock(Calendar.Events.Instances.class);
        when(api.instances("primary", "google-1")).thenReturn(instancesRequest);
        when(instancesRequest.setOriginalStart(anyString())).thenReturn(instancesRequest);
        when(instancesRequest.setShowDeleted(true)).thenReturn(instancesRequest);
        when(instancesRequest.setMaxResults(2)).thenReturn(instancesRequest);
        Event instance = new Event().setId("google-instance-authoritative").setEtag("instance-etag")
                .setRecurringEventId("google-1")
                .setOriginalStartTime(new EventDateTime()
                        .setDateTime(new DateTime("2026-09-27T09:00:00+08:00")))
                .setStart(new EventDateTime().setDateTime(new DateTime("2026-09-27T09:00:00+08:00")))
                .setEnd(new EventDateTime().setDateTime(new DateTime("2026-09-27T10:00:00+08:00")));
        when(instancesRequest.execute()).thenReturn(new Events().setItems(List.of(instance)));

        Calendar.Events.Update update = mock(Calendar.Events.Update.class);
        when(api.update("primary", "google-instance-authoritative", instance)).thenReturn(update);
        HttpHeaders headers = new HttpHeaders();
        when(update.getRequestHeaders()).thenReturn(headers);
        Event returned = instance.clone().setEtag("instance-etag-2");
        when(update.execute()).thenReturn(returned);

        GoogleEventUpdateRequest recurringRequest = new GoogleEventUpdateRequest(
                request(EventAudienceType.FAMILY, List.of(), false, LocalDate.of(2026, 9, 28)),
                GoogleEventEditScope.THIS_EVENT, LocalDate.of(2026, 9, 27));
        service.update(family, local.getId(), recurringRequest);

        verify(instancesRequest).setOriginalStart("2026-09-27T09:00:00+08:00");
        assertThat(headers.getIfMatch()).isEqualTo("instance-etag");
        verify(persistence).persistOccurrence(family, null, local.getId(), calendar.getId(),
                "google-instance-authoritative", returned, EventAudienceType.FAMILY, List.of());
        assertThat(googleParent.getRecurrence()).containsExactly("RRULE:FREQ=WEEKLY;BYDAY=SU");
    }

    @Test
    void editsVirtualAllDayOccurrenceUsingGoogleResolvedInstanceIdentity() throws IOException {
        local.setRecurrenceRule("RRULE:FREQ=WEEKLY;BYDAY=SU");
        local.setAllDay(true);
        local.setStartTime(java.time.LocalTime.MIDNIGHT);
        available("owner").setStart(new EventDateTime().setDate(new DateTime("2026-09-20")))
                .setEnd(new EventDateTime().setDate(new DateTime("2026-09-21")))
                .setRecurrence(List.of("RRULE:FREQ=WEEKLY;BYDAY=SU"));

        Calendar.Events.Instances instancesRequest = mock(Calendar.Events.Instances.class);
        when(api.instances("primary", "google-1")).thenReturn(instancesRequest);
        when(instancesRequest.setOriginalStart(anyString())).thenReturn(instancesRequest);
        when(instancesRequest.setShowDeleted(true)).thenReturn(instancesRequest);
        when(instancesRequest.setMaxResults(2)).thenReturn(instancesRequest);
        Event instance = new Event().setId("google-all-day-instance").setEtag("instance-etag")
                .setRecurringEventId("google-1")
                .setOriginalStartTime(new EventDateTime().setDate(new DateTime("2026-09-27")))
                .setStart(new EventDateTime().setDate(new DateTime("2026-09-27")))
                .setEnd(new EventDateTime().setDate(new DateTime("2026-09-28")));
        when(instancesRequest.execute()).thenReturn(new Events().setItems(List.of(instance)));
        Calendar.Events.Update update = mock(Calendar.Events.Update.class);
        when(api.update("primary", "google-all-day-instance", instance)).thenReturn(update);
        when(update.getRequestHeaders()).thenReturn(new HttpHeaders());
        when(update.execute()).thenReturn(instance);

        service.update(family, local.getId(), new GoogleEventUpdateRequest(
                request(EventAudienceType.FAMILY, List.of(), true, LocalDate.of(2026, 9, 27)),
                GoogleEventEditScope.THIS_EVENT, LocalDate.of(2026, 9, 27)));

        verify(instancesRequest).setOriginalStart("2026-09-27T00:00:00+08:00");
        verify(api).update("primary", "google-all-day-instance", instance);
        verify(persistence).persistOccurrence(family, null, local.getId(), calendar.getId(),
                "google-all-day-instance", instance, EventAudienceType.FAMILY, List.of());
    }

    @Test
    void staleOccurrenceEtagRefreshesOnlyThatExceptionAndReturnsConflict() throws IOException {
        CalendarEvent parent = local;
        parent.setRecurrenceRule("RRULE:FREQ=WEEKLY;BYDAY=SU");
        parent.setGoogleEventId("google-parent");
        CalendarEvent exception = new CalendarEvent();
        exception.setId(UUID.randomUUID());
        exception.setFamily(family);
        exception.setSource(EventSource.GOOGLE);
        exception.setSourceOwnerMember(member);
        exception.setSyncedCalendar(calendar);
        exception.setGoogleEventId("google-instance");
        exception.setEtag("old-etag");
        exception.setRecurringEvent(parent);
        when(events.findGoogleEventForWrite(family, exception.getId())).thenReturn(Optional.of(exception));
        when(events.findGoogleEventForWrite(family, parent.getId())).thenReturn(Optional.of(parent));
        when(tokens.findByMemberId(member.getId())).thenReturn(Optional.of(token));
        when(discovery.listCalendars(member.getId())).thenReturn(List.of(
                new GoogleCalendarInfo("primary", "Personal", true, "owner")));
        doReturn(client).when(service).buildClient(member.getId());
        when(client.events()).thenReturn(api);
        Calendar.Events.Get instanceGet = mock(Calendar.Events.Get.class);
        when(api.get("primary", "google-instance")).thenReturn(instanceGet);
        Event current = new Event().setId("google-instance").setEtag("new-etag")
                .setRecurringEventId("google-parent");
        when(instanceGet.execute()).thenReturn(current);

        assertThatThrownBy(() -> service.update(family, exception.getId(),
                new GoogleEventUpdateRequest(
                        request(EventAudienceType.FAMILY, List.of(), false, LocalDate.of(2026, 9, 28)),
                        GoogleEventEditScope.THIS_EVENT, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("changed in Google");

        verify(persistence).persistOccurrence(family, exception.getId(), parent.getId(), calendar.getId(),
                "google-instance", current, null, List.of());
        verify(api, never()).update(anyString(), anyString(), any());
    }

    @Test
    void missingRecurringOccurrenceReconcilesItsLocalExceptionWithoutUpdatingParent() throws IOException {
        CalendarEvent parent = local;
        parent.setRecurrenceRule("RRULE:FREQ=WEEKLY;BYDAY=SU");
        parent.setGoogleEventId("google-parent");
        CalendarEvent exception = new CalendarEvent();
        exception.setId(UUID.randomUUID());
        exception.setFamily(family);
        exception.setSource(EventSource.GOOGLE);
        exception.setSourceOwnerMember(member);
        exception.setSyncedCalendar(calendar);
        exception.setGoogleEventId("google-instance");
        exception.setRecurringEvent(parent);
        when(events.findGoogleEventForWrite(family, exception.getId())).thenReturn(Optional.of(exception));
        when(events.findGoogleEventForWrite(family, parent.getId())).thenReturn(Optional.of(parent));
        when(tokens.findByMemberId(member.getId())).thenReturn(Optional.of(token));
        when(discovery.listCalendars(member.getId())).thenReturn(List.of(
                new GoogleCalendarInfo("primary", "Personal", true, "owner")));
        doReturn(client).when(service).buildClient(member.getId());
        when(client.events()).thenReturn(api);
        Calendar.Events.Get instanceGet = mock(Calendar.Events.Get.class);
        when(api.get("primary", "google-instance")).thenReturn(instanceGet);
        GoogleJsonResponseException gone = mock(GoogleJsonResponseException.class);
        when(gone.getStatusCode()).thenReturn(410);
        when(instanceGet.execute()).thenThrow(gone);

        assertThatThrownBy(() -> service.update(family, exception.getId(),
                new GoogleEventUpdateRequest(
                        request(EventAudienceType.FAMILY, List.of(), false, LocalDate.of(2026, 9, 28)),
                        GoogleEventEditScope.THIS_EVENT, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(deletionPersistence).reconcile(family, exception.getId(), calendar.getId(), "google-instance");
        verify(deletionPersistence, never()).reconcile(family, parent.getId(), calendar.getId(), "google-parent");
    }

    @Test
    void editsSeriesParentWithoutChangingTimingOrRecurrenceAndResolvesParentServerSide() throws IOException {
        CalendarEvent parent = local;
        parent.setRecurrenceRule("RRULE:FREQ=WEEKLY;BYDAY=SU");
        parent.setGoogleEventId("google-parent");
        CalendarEvent exception = new CalendarEvent();
        exception.setId(UUID.randomUUID());
        exception.setFamily(family);
        exception.setSource(EventSource.GOOGLE);
        exception.setSourceOwnerMember(member);
        exception.setSyncedCalendar(calendar);
        exception.setGoogleEventId("google-instance");
        exception.setRecurringEvent(parent);
        when(events.findGoogleEventForWrite(family, exception.getId())).thenReturn(Optional.of(exception));
        when(events.findGoogleEventForWrite(family, parent.getId())).thenReturn(Optional.of(parent));
        when(tokens.findByMemberId(member.getId())).thenReturn(Optional.of(token));
        when(discovery.listCalendars(member.getId())).thenReturn(List.of(
                new GoogleCalendarInfo("primary", "Personal", true, "owner")));
        doReturn(client).when(service).buildClient(member.getId());
        when(client.events()).thenReturn(api);
        Calendar.Events.Get parentGet = mock(Calendar.Events.Get.class);
        when(api.get("primary", "google-parent")).thenReturn(parentGet);
        Event current = new Event().setId("google-parent").setEtag("etag-1")
                .setStart(new EventDateTime().setDateTime(new DateTime("2026-09-20T09:00:00+08:00")))
                .setEnd(new EventDateTime().setDateTime(new DateTime("2026-09-20T10:00:00+08:00")))
                .setRecurrence(List.of("RRULE:FREQ=WEEKLY;BYDAY=SU", "EXDATE:20261004T010000Z"));
        when(parentGet.execute()).thenReturn(current);
        Calendar.Events.Update update = mock(Calendar.Events.Update.class);
        when(api.update("primary", "google-parent", current)).thenReturn(update);
        when(update.getRequestHeaders()).thenReturn(new HttpHeaders());
        when(update.execute()).thenReturn(current);

        service.update(family, exception.getId(), new GoogleEventUpdateRequest(
                request(EventAudienceType.FAMILY, List.of(), false, null),
                GoogleEventEditScope.ENTIRE_SERIES, null));

        assertThat(current.getStart().getDateTime().toStringRfc3339())
                .isEqualTo("2026-09-20T09:00:00.000+08:00");
        assertThat(current.getRecurrence()).containsExactly(
                "RRULE:FREQ=WEEKLY;BYDAY=SU", "EXDATE:20261004T010000Z");
        verify(api).update("primary", "google-parent", current);
        verify(persistence).persistSeries(family, parent.getId(), calendar.getId(),
                "google-parent", current, EventAudienceType.FAMILY, List.of());
    }

    @Test
    void staleSeriesEtagRefreshesParentAndReturnsConflictWithoutGoogleUpdate() throws IOException {
        local.setRecurrenceRule("RRULE:FREQ=WEEKLY;BYDAY=SU");
        Event current = available("owner").setEtag("external-etag")
                .setRecurrence(List.of("RRULE:FREQ=WEEKLY;BYDAY=SU"));

        assertThatThrownBy(() -> service.update(family, local.getId(),
                new GoogleEventUpdateRequest(
                        request(EventAudienceType.FAMILY, List.of(), false, null),
                        GoogleEventEditScope.ENTIRE_SERIES, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("changed in Google");

        verify(persistence).persistSeries(family, local.getId(), calendar.getId(),
                "google-1", current, null, List.of());
        verify(api, never()).update(anyString(), anyString(), any());
    }

    @Test
    void seriesGoogleFailureKeepsLocalAndPersistenceFailureIsExplicitlyUncertain() throws IOException {
        local.setRecurrenceRule("RRULE:FREQ=WEEKLY;BYDAY=SU");
        Event current = available("owner").setRecurrence(List.of("RRULE:FREQ=WEEKLY;BYDAY=SU"));
        Calendar.Events.Update update = mock(Calendar.Events.Update.class);
        when(api.update("primary", "google-1", current)).thenReturn(update);
        when(update.getRequestHeaders()).thenReturn(new HttpHeaders());
        when(update.execute()).thenThrow(new IOException("network"));
        GoogleEventUpdateRequest request = new GoogleEventUpdateRequest(
                request(EventAudienceType.FAMILY, List.of(), false, null),
                GoogleEventEditScope.ENTIRE_SERIES, null);

        assertThatThrownBy(() -> service.update(family, local.getId(), request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("kept");
        verifyNoInteractions(persistence);

        reset(update, persistence);
        when(update.getRequestHeaders()).thenReturn(new HttpHeaders());
        Event returned = new Event().setId("google-1").setEtag("etag-2");
        when(update.execute()).thenReturn(returned);
        when(persistence.persistSeries(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db"));

        assertThatThrownBy(() -> service.update(family, local.getId(), request))
                .isInstanceOf(GoogleWriteUncertainException.class)
                .hasMessageContaining("Sync Now");
    }
}
