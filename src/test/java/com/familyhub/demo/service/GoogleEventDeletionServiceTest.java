package com.familyhub.demo.service;

import com.familyhub.demo.dto.GoogleCalendarInfo;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.calendar.Calendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleEventDeletionServiceTest {
    @Mock CalendarEventRepository events;
    @Mock GoogleOAuthTokenRepository tokens;
    @Mock GoogleCalendarListService discovery;
    @Mock GoogleCredentialService credentials;
    @Mock GoogleEventDeletionPersistenceService persistence;
    @Spy @InjectMocks GoogleEventDeletionService service;

    Family family;
    FamilyMember member;
    GoogleOAuthToken token;
    GoogleSyncedCalendar calendar;
    CalendarEvent event;

    @BeforeEach
    void setup() {
        family = new Family();
        family.setId(UUID.randomUUID());
        member = new FamilyMember();
        member.setId(UUID.randomUUID());
        member.setFamily(family);
        token = new GoogleOAuthToken();
        token.setId(UUID.randomUUID());
        token.setMember(member);
        token.setScope(GoogleOAuthService.EVENT_WRITE_SCOPE);
        calendar = new GoogleSyncedCalendar();
        calendar.setId(UUID.randomUUID());
        calendar.setMember(member);
        calendar.setToken(token);
        calendar.setGoogleCalendarId("primary");
        calendar.setEnabled(true);
        event = new CalendarEvent();
        event.setId(UUID.randomUUID());
        event.setFamily(family);
        event.setSource(EventSource.GOOGLE);
        event.setSourceOwnerMember(member);
        event.setSyncedCalendar(calendar);
        event.setGoogleEventId("google-event-1");
    }

    private Calendar.Events.Delete available(String role) throws IOException {
        when(events.findGoogleEventForWrite(family, event.getId())).thenReturn(Optional.of(event));
        when(tokens.findByMemberId(member.getId())).thenReturn(Optional.of(token));
        when(discovery.listCalendars(member.getId())).thenReturn(List.of(
                new GoogleCalendarInfo("primary", "Personal", true, role)));
        Calendar client = mock(Calendar.class);
        Calendar.Events api = mock(Calendar.Events.class);
        Calendar.Events.Delete delete = mock(Calendar.Events.Delete.class);
        doReturn(client).when(service).buildClient(member.getId());
        when(client.events()).thenReturn(api);
        when(api.delete("primary", "google-event-1")).thenReturn(delete);
        return delete;
    }

    @Test
    void writableDeleteCallsGoogleBeforeRemovingScopedLocalRow() throws IOException {
        Calendar.Events.Delete delete = available("writer");

        service.delete(family, event.getId());

        InOrder order = inOrder(delete, persistence);
        order.verify(delete).execute();
        order.verify(persistence).reconcile(family, event.getId(), calendar.getId(), "google-event-1");
    }

    @Test
    void googleFailureLeavesLocalRowUntouched() throws IOException {
        Calendar.Events.Delete delete = available("owner");
        when(delete.execute()).thenThrow(new IOException("network"));

        assertThatThrownBy(() -> service.delete(family, event.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("kept");
        verifyNoInteractions(persistence);
    }

    @Test
    void alreadyMissingGoogleEventReconcilesLocalRow() throws IOException {
        Calendar.Events.Delete delete = available("owner");
        GoogleJsonResponseException missing = mock(GoogleJsonResponseException.class);
        when(missing.getStatusCode()).thenReturn(404);
        when(delete.execute()).thenThrow(missing);

        service.delete(family, event.getId());

        verify(persistence).reconcile(family, event.getId(), calendar.getId(), "google-event-1");
    }

    @Test
    void readOnlyCalendarAndMissingWriteScopeAreRejectedBeforeGoogle() {
        when(events.findGoogleEventForWrite(family, event.getId())).thenReturn(Optional.of(event));
        when(tokens.findByMemberId(member.getId())).thenReturn(Optional.of(token));
        when(discovery.listCalendars(member.getId())).thenReturn(List.of(
                new GoogleCalendarInfo("primary", "Personal", true, "reader")));
        assertThatThrownBy(() -> service.delete(family, event.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("read-only");

        token.setScope("https://www.googleapis.com/auth/calendar.events.readonly");
        assertThatThrownBy(() -> service.delete(family, event.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("no longer writable");
        verify(service, never()).buildClient(any());
    }

    @Test
    void foreignFamilyAndMismatchedCalendarConnectionAreRejected() {
        Family otherFamily = new Family();
        otherFamily.setId(UUID.randomUUID());
        when(events.findGoogleEventForWrite(otherFamily, event.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(otherFamily, event.getId()))
                .isInstanceOf(ResourceNotFoundException.class);

        when(events.findGoogleEventForWrite(family, event.getId())).thenReturn(Optional.of(event));
        when(tokens.findByMemberId(member.getId())).thenReturn(Optional.of(token));
        GoogleOAuthToken anotherToken = new GoogleOAuthToken();
        anotherToken.setId(UUID.randomUUID());
        calendar.setToken(anotherToken);
        assertThatThrownBy(() -> service.delete(family, event.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("no longer writable");
        verify(service, never()).buildClient(any());
    }

    @Test
    void recurringGoogleEventIsRejected() {
        event.setRecurrenceRule("RRULE:FREQ=DAILY");
        when(events.findGoogleEventForWrite(family, event.getId())).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.delete(family, event.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Recurring Google");
        verifyNoInteractions(tokens, discovery, persistence);
    }

    @Test
    void nativeEventIsNotResolvedByTheGoogleDeletePath() {
        event.setSource(EventSource.NATIVE);
        when(events.findGoogleEventForWrite(family, event.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(family, event.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(tokens, discovery, persistence);
    }
}
