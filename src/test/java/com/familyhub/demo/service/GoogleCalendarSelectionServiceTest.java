package com.familyhub.demo.service;

import com.familyhub.demo.dto.GoogleCalendarInfo;
import com.familyhub.demo.dto.GoogleCalendarResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.repository.GoogleSyncedCalendarRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleCalendarSelectionServiceTest {

    @Mock
    private GoogleOAuthTokenRepository tokenRepository;

    @Mock
    private GoogleSyncedCalendarRepository syncedCalendarRepository;

    @Mock
    private GoogleCalendarListService calendarListService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private CalendarEventRepository calendarEventRepository;

    @InjectMocks
    private GoogleCalendarSelectionService selectionService;

    private static final UUID MEMBER_ID = UUID.randomUUID();

    @Test
    void listCalendarsWithSelections_mergesGoogleAndStored() {
        GoogleOAuthToken token = new GoogleOAuthToken();
        when(tokenRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(token));

        when(calendarListService.listCalendars(MEMBER_ID)).thenReturn(List.of(
                new GoogleCalendarInfo("primary", "Joe's Calendar", true),
                new GoogleCalendarInfo("work@group", "Work", false)
        ));

        GoogleSyncedCalendar synced = new GoogleSyncedCalendar();
        synced.setGoogleCalendarId("primary");
        synced.setEnabled(true);
        when(syncedCalendarRepository.findByMemberId(MEMBER_ID)).thenReturn(List.of(synced));

        List<GoogleCalendarResponse> result = selectionService.listCalendarsWithSelections(MEMBER_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo("primary");
        assertThat(result.get(0).enabled()).isTrue();
        assertThat(result.get(0).primary()).isTrue();
        assertThat(result.get(1).id()).isEqualTo("work@group");
        assertThat(result.get(1).enabled()).isFalse();
    }

    @Test
    void updateCalendarSelections_createsNewRows() {
        GoogleOAuthToken token = new GoogleOAuthToken();
        FamilyMember member = new FamilyMember();
        token.setMember(member);
        when(tokenRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(token));

        when(calendarListService.listCalendars(MEMBER_ID)).thenReturn(List.of(
                new GoogleCalendarInfo("primary", "Joe's Calendar", true)
        ));
        when(syncedCalendarRepository.findByMemberIdForUpdate(MEMBER_ID)).thenReturn(List.of());

        selectionService.updateCalendarSelections(MEMBER_ID, List.of("primary"));

        ArgumentCaptor<GoogleSyncedCalendar> captor = ArgumentCaptor.forClass(GoogleSyncedCalendar.class);
        verify(syncedCalendarRepository).save(captor.capture());

        GoogleSyncedCalendar saved = captor.getValue();
        assertThat(saved.getGoogleCalendarId()).isEqualTo("primary");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getCalendarName()).isEqualTo("Joe's Calendar");
    }

    @Test
    void updateCalendarSelections_disablesDeselected() {
        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setMember(new FamilyMember());
        when(tokenRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(token));

        when(calendarListService.listCalendars(MEMBER_ID)).thenReturn(List.of(
                new GoogleCalendarInfo("primary", "Joe's Calendar", true),
                new GoogleCalendarInfo("work@group", "Work", false)
        ));

        GoogleSyncedCalendar existing = new GoogleSyncedCalendar();
        existing.setGoogleCalendarId("primary");
        existing.setEnabled(true);

        GoogleSyncedCalendar workCal = new GoogleSyncedCalendar();
        workCal.setGoogleCalendarId("work@group");
        workCal.setEnabled(true);

        when(syncedCalendarRepository.findByMemberIdForUpdate(MEMBER_ID)).thenReturn(List.of(existing, workCal));

        // Only select "primary", deselect "work@group"
        List<GoogleCalendarResponse> result = selectionService.updateCalendarSelections(MEMBER_ID, List.of("primary"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).enabled()).isTrue();
        assertThat(result.get(1).enabled()).isFalse();

        // work@group should have been disabled
        assertThat(workCal.isEnabled()).isFalse();
        verify(calendarEventRepository).deleteBySyncedCalendarAndSource(workCal, EventSource.GOOGLE);
        verify(syncedCalendarRepository, times(2)).save(any());
    }

    @Test
    void reenablePreviouslyDisabledCalendarForcesFullImport() {
        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setMember(new FamilyMember());
        when(tokenRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(token));
        when(calendarListService.listCalendars(MEMBER_ID))
                .thenReturn(List.of(new GoogleCalendarInfo("primary", "Primary", true)));
        GoogleSyncedCalendar stored = new GoogleSyncedCalendar();
        stored.setGoogleCalendarId("primary");
        stored.setEnabled(false);
        stored.setSyncToken("old-token");
        when(syncedCalendarRepository.findByMemberIdForUpdate(MEMBER_ID)).thenReturn(List.of(stored));

        selectionService.updateCalendarSelections(MEMBER_ID, List.of("primary"));

        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getSyncToken()).isNull();
        verify(calendarEventRepository, never()).deleteBySyncedCalendarAndSource(any(), any());
    }

    @Test
    void successfulDiscoveryDisablesOnlyMissingEnabledCalendar() {
        GoogleSyncedCalendar missing = new GoogleSyncedCalendar();
        missing.setId(UUID.randomUUID());
        missing.setGoogleCalendarId("removed");
        missing.setEnabled(true);
        missing.setSyncToken("old-token");
        missing.setLastSyncedAt(java.time.Instant.now());
        GoogleSyncedCalendar present = new GoogleSyncedCalendar();
        present.setId(UUID.randomUUID());
        present.setGoogleCalendarId("hidden-but-present");
        present.setEnabled(true);
        when(syncedCalendarRepository.findByMemberIdForUpdate(MEMBER_ID))
                .thenReturn(List.of(missing, present));

        selectionService.disableMissingCalendars(MEMBER_ID,
                java.util.Set.of(missing.getId(), present.getId()), java.util.Set.of("hidden-but-present"));

        assertThat(missing.isEnabled()).isFalse();
        assertThat(missing.getSyncToken()).isNull();
        assertThat(missing.getLastSyncedAt()).isNull();
        assertThat(present.isEnabled()).isTrue();
        verify(calendarEventRepository).deleteBySyncedCalendarAndSource(missing, EventSource.GOOGLE);
        verify(calendarEventRepository, never()).deleteBySyncedCalendarAndSource(present, EventSource.GOOGLE);
    }

    @Test
    void oldDiscoveryCannotDisableSelectionCreatedAfterReconnect() {
        GoogleSyncedCalendar replacement = new GoogleSyncedCalendar();
        replacement.setId(UUID.randomUUID());
        replacement.setGoogleCalendarId("new-account-calendar");
        replacement.setEnabled(true);
        when(syncedCalendarRepository.findByMemberIdForUpdate(MEMBER_ID)).thenReturn(List.of(replacement));

        selectionService.disableMissingCalendars(MEMBER_ID, java.util.Set.of(UUID.randomUUID()), java.util.Set.of());

        assertThat(replacement.isEnabled()).isTrue();
        verifyNoInteractions(calendarEventRepository);
    }

    @Test
    void selectionUpdateAlsoDisablesCalendarMissingFromCompleteDiscovery() {
        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setMember(new FamilyMember());
        when(tokenRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(token));
        when(calendarListService.listCalendars(MEMBER_ID))
                .thenReturn(List.of(new GoogleCalendarInfo("present", "Present", true)));
        GoogleSyncedCalendar missing = new GoogleSyncedCalendar();
        missing.setGoogleCalendarId("removed");
        missing.setEnabled(true);
        when(syncedCalendarRepository.findByMemberIdForUpdate(MEMBER_ID)).thenReturn(List.of(missing));

        selectionService.updateCalendarSelections(MEMBER_ID, List.of("present"));

        assertThat(missing.isEnabled()).isFalse();
        verify(calendarEventRepository).deleteBySyncedCalendarAndSource(missing, EventSource.GOOGLE);
    }

    @Test
    void failedDiscoveryDuringSelectionUpdateDoesNotDeleteImportedEvents() {
        GoogleOAuthToken token = new GoogleOAuthToken();
        when(tokenRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(token));
        when(calendarListService.listCalendars(MEMBER_ID))
                .thenThrow(new RuntimeException("second page failed"));

        assertThatThrownBy(() -> selectionService.updateCalendarSelections(MEMBER_ID, List.of()))
                .hasMessageContaining("second page failed");
        verifyNoInteractions(calendarEventRepository, syncedCalendarRepository);
    }

    @Test
    void listCalendarsWithSelections_notConnected_throwsBadRequest() {
        when(tokenRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> selectionService.listCalendarsWithSelections(MEMBER_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not connected");
    }

    @Test
    void updateCalendarSelections_notConnected_throwsBadRequest() {
        when(tokenRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> selectionService.updateCalendarSelections(MEMBER_ID, List.of("primary")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not connected");
    }
}
