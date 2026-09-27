package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.dto.GoogleCalendarInfo;
import com.familyhub.demo.dto.GoogleEventCreateRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.EventAudienceType;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.repository.GoogleSyncedCalendarRepository;
import com.google.api.services.calendar.Calendar;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.calendar.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class GoogleEventCreationServiceTest {
    @Mock FamilyMemberRepository members;
    @Mock GoogleOAuthTokenRepository tokens;
    @Mock GoogleSyncedCalendarRepository calendars;
    @Mock GoogleCalendarListService discovery;
    @Mock GoogleCredentialService credentials;
    @Mock GoogleEventPersistenceService persistence;
    @Spy @InjectMocks GoogleEventCreationService service;

    Family family;
    FamilyMember member;
    GoogleOAuthToken token;
    GoogleSyncedCalendar selected;

    @BeforeEach
    void setup() {
        family = new Family(); family.setId(UUID.randomUUID()); family.setTimezone("Australia/Perth");
        member = new FamilyMember(); member.setId(UUID.randomUUID()); member.setFamily(family); member.setName("James");
        token = new GoogleOAuthToken(); token.setId(UUID.randomUUID()); token.setMember(member);
        token.setScope(GoogleOAuthService.EVENT_WRITE_SCOPE);
        selected = new GoogleSyncedCalendar(); selected.setId(UUID.randomUUID()); selected.setToken(token);
        selected.setMember(member); selected.setGoogleCalendarId("primary"); selected.setEnabled(true);
    }

    private CalendarEventRequest input(EventAudienceType audience, List<UUID> ids, LocalDate endDate) {
        return new CalendarEventRequest("Dinner", "11:00 PM", "8:00 AM", LocalDate.of(2025, 6, 15),
                audience, ids, false, "Home", endDate, null, "Notes");
    }

    private GoogleEventCreateRequest request(CalendarEventRequest event) {
        return new GoogleEventCreateRequest(member.getId(), selected.getId(), UUID.randomUUID(), event);
    }

    private void available(String role) {
        when(members.findById(member.getId())).thenReturn(Optional.of(member));
        when(calendars.findById(selected.getId())).thenReturn(Optional.of(selected));
        when(tokens.findByMemberId(member.getId())).thenReturn(Optional.of(token));
        lenient().when(discovery.listCalendars(member.getId()))
                .thenReturn(List.of(new GoogleCalendarInfo("primary", "Personal", true, role)));
    }

    @Test
    void destinationListsOnlyWritableSelectedCalendarsAndMissingScope() {
        when(members.findByFamily(family)).thenReturn(List.of(member));
        when(tokens.findByMemberId(member.getId())).thenReturn(Optional.of(token));
        when(discovery.listCalendars(member.getId())).thenReturn(List.of(
                new GoogleCalendarInfo("primary", "Personal", true, "writer"),
                new GoogleCalendarInfo("reader", "Subscribed", false, "reader")));
        when(calendars.findByMemberIdAndEnabledTrue(member.getId())).thenReturn(List.of(selected));
        assertThat(service.destinations(family).destinations()).hasSize(1);
        token.setScope("https://www.googleapis.com/auth/calendar.events.readonly");
        assertThat(service.destinations(family).reconnectMemberIds()).containsExactly(member.getId());
    }

    @Test
    void readerAndFreeBusyAreRejectedBeforeGoogleWrite() {
        for (String role : List.of("reader", "freeBusyReader")) {
            available(role);
            assertThatThrownBy(() -> service.create(family, request(input(EventAudienceType.FAMILY, List.of(), null))))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("read-only");
        }
        verify(service, never()).buildClient(any());
    }

    @Test
    void missingScopeAndForeignMemberAreRejected() {
        available("owner");
        token.setScope("https://www.googleapis.com/auth/calendar.events.readonly");
        assertThatThrownBy(() -> service.create(family, request(input(EventAudienceType.FAMILY, List.of(), null))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Reconnect");
        token.setScope(GoogleOAuthService.EVENT_WRITE_SCOPE);
        Family other = new Family(); other.setId(UUID.randomUUID());
        assertThatThrownBy(() -> service.create(other, request(input(EventAudienceType.FAMILY, List.of(), null))))
                .isInstanceOf(BadRequestException.class);
        verify(service, never()).buildClient(any());
    }

    @Test
    void anotherMembersCalendarCannotBeUsedWithThisConnection() {
        available("owner");
        FamilyMember other = new FamilyMember();
        other.setId(UUID.randomUUID()); other.setFamily(family);
        selected.setMember(other);
        assertThatThrownBy(() -> service.create(family, request(input(EventAudienceType.FAMILY, List.of(), null))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("not selected");
        verify(service, never()).buildClient(any());
    }

    @Test
    void duplicateRequestIdFetchesExistingGoogleEventInsteadOfInsertingAgain() throws IOException {
        available("owner");
        Calendar client = mock(Calendar.class);
        Calendar.Events api = mock(Calendar.Events.class);
        Calendar.Events.Insert insert = mock(Calendar.Events.Insert.class);
        Calendar.Events.Get get = mock(Calendar.Events.Get.class);
        GoogleJsonResponseException duplicate = mock(GoogleJsonResponseException.class);
        when(duplicate.getStatusCode()).thenReturn(409);
        doReturn(client).when(service).buildClient(member.getId());
        when(client.events()).thenReturn(api);
        when(api.insert(eq("primary"), any(Event.class))).thenReturn(insert);
        when(insert.execute()).thenThrow(duplicate);
        when(api.get(eq("primary"), anyString())).thenReturn(get);
        when(get.execute()).thenReturn(new Event().setId("existing"));

        service.create(family, request(input(EventAudienceType.FAMILY, List.of(), LocalDate.of(2025, 6, 16))));

        verify(insert, times(1)).execute();
        verify(get, times(1)).execute();
        verify(persistence).persist(eq(selected.getId()), eq(member.getId()), eq(family),
                any(Event.class), eq(EventAudienceType.FAMILY), eq(List.of()));
    }

    @Test
    void ownerAndWriterCreateOneGoogleEventWithoutAttendees() throws IOException {
        for (String role : List.of("writer", "owner", "writerWithoutPrivateAccess")) {
            available(role);
            Calendar client = mock(Calendar.class);
            Calendar.Events api = mock(Calendar.Events.class);
            Calendar.Events.Insert insert = mock(Calendar.Events.Insert.class);
            doReturn(client).when(service).buildClient(member.getId());
            when(client.events()).thenReturn(api);
            when(api.insert(eq("primary"), any(Event.class))).thenReturn(insert);
            when(insert.execute()).thenReturn(new Event().setId("google-created").setEtag("etag"));
            service.create(family, request(input(EventAudienceType.FAMILY, List.of(), LocalDate.of(2025, 6, 16))));
            verifyEventArgument(api);
            verify(persistence, atLeastOnce()).persist(eq(selected.getId()), eq(member.getId()), eq(family),
                    any(Event.class), eq(EventAudienceType.FAMILY), eq(List.of()));
            reset(api, insert, persistence);
        }
    }

    private Event verifyEventArgument(Calendar.Events api) throws IOException {
        ArgumentCaptor<Event> capture = ArgumentCaptor.forClass(Event.class);
        verify(api).insert(eq("primary"), capture.capture());
        Event event = capture.getValue();
        assertThat(event.getAttendees()).isNull();
        assertThat(event.getId()).startsWith("fh");
        return event;
    }

    @Test
    void googleFailureDoesNotPersistAndLocalFailureDoesNotRepeatInsert() throws IOException {
        available("owner");
        Calendar client = mock(Calendar.class);
        Calendar.Events api = mock(Calendar.Events.class);
        Calendar.Events.Insert insert = mock(Calendar.Events.Insert.class);
        doReturn(client).when(service).buildClient(member.getId());
        when(client.events()).thenReturn(api);
        when(api.insert(eq("primary"), any(Event.class))).thenReturn(insert);
        when(insert.execute()).thenThrow(new IOException("network"));
        var req = request(input(EventAudienceType.FAMILY, List.of(), LocalDate.of(2025, 6, 16)));
        assertThatThrownBy(() -> service.create(family, req)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(persistence);
        reset(insert);
        when(insert.execute()).thenReturn(new Event().setId("google-created"));
        when(persistence.persist(any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("db"));
        assertThatThrownBy(() -> service.create(family, req))
                .hasMessageContaining("Use Sync Now");
        verify(api, times(2)).insert(eq("primary"), any(Event.class));
    }

    @Test
    void recurringCreationIsRejected() {
        available("writer");
        var recurring = new CalendarEventRequest("Repeat", "9:00 AM", "10:00 AM", LocalDate.of(2025, 6, 15),
                EventAudienceType.FAMILY, List.of(), false, null, null, "FREQ=DAILY", null);
        assertThatThrownBy(() -> service.create(family, request(recurring)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Recurring Google");
    }

    @Test
    void googleShapesPreserveAllDaySpanAndOvernightTimezone() {
        Event allDay = GoogleEventCreationService.toGoogleEvent(new CalendarEventRequest("Holiday", "12:00 AM", "12:00 AM",
                LocalDate.of(2025, 6, 15), EventAudienceType.FAMILY, List.of(), true, null,
                LocalDate.of(2025, 6, 17), null, null), "Australia/Perth", UUID.randomUUID());
        assertThat(allDay.getStart().getDate().toStringRfc3339()).isEqualTo("2025-06-15");
        assertThat(allDay.getEnd().getDate().toStringRfc3339()).isEqualTo("2025-06-18");
        Event overnight = GoogleEventCreationService.toGoogleEvent(input(EventAudienceType.FAMILY, List.of(),
                LocalDate.of(2025, 6, 16)), "Australia/Perth", UUID.randomUUID());
        assertThat(overnight.getStart().getDateTime().toStringRfc3339()).contains("2025-06-15T23:00:00.000+08:00");
        assertThat(overnight.getEnd().getDateTime().toStringRfc3339()).contains("2025-06-16T08:00:00.000+08:00");
        assertThat(overnight.getAttendees()).isNull();
        assertThat(GoogleEventCreationService.toGoogleEvent(
                input(EventAudienceType.MEMBERS, List.of(member.getId()), LocalDate.of(2025, 6, 16)),
                "Australia/Perth", UUID.randomUUID()).getAttendees()).isNull();
        GoogleEventMapper mapper = new GoogleEventMapper();
        var allDayImported = mapper.toEntity(allDay, selected);
        assertThat(allDayImported.getDate()).isEqualTo(LocalDate.of(2025, 6, 15));
        assertThat(allDayImported.getEndDate()).isEqualTo(LocalDate.of(2025, 6, 17));
        var overnightImported = mapper.toEntity(overnight, selected);
        assertThat(overnightImported.getDate()).isEqualTo(LocalDate.of(2025, 6, 15));
        assertThat(overnightImported.getEndDate()).isEqualTo(LocalDate.of(2025, 6, 16));
        assertThat(overnightImported.getStartTime()).isEqualTo(java.time.LocalTime.of(23, 0));
        assertThat(overnightImported.getEndTime()).isEqualTo(java.time.LocalTime.of(8, 0));
    }
}
