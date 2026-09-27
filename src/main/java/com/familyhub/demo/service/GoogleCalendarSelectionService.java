package com.familyhub.demo.service;

import com.familyhub.demo.dto.GoogleCalendarInfo;
import com.familyhub.demo.dto.GoogleCalendarResponse;
import com.familyhub.demo.event.SyncRequestedEvent;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.GoogleSyncedCalendarRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GoogleCalendarSelectionService {
    private final GoogleOAuthTokenRepository tokenRepository;
    private final GoogleSyncedCalendarRepository syncedCalendarRepository;
    private final GoogleCalendarListService calendarListService;
    private final ApplicationEventPublisher eventPublisher;
    private final CalendarEventRepository calendarEventRepository;

    public List<GoogleCalendarResponse> listCalendarsWithSelections(UUID memberId) {
        tokenRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BadRequestException("Google account not connected"));

        List<GoogleCalendarInfo> googleCalendars = calendarListService.listCalendars(memberId);

        Map<String, GoogleSyncedCalendar> storedByGoogleId = syncedCalendarRepository.findByMemberId(memberId)
                .stream()
                .collect(Collectors.toMap(GoogleSyncedCalendar::getGoogleCalendarId, Function.identity()));

        return googleCalendars.stream()
                .map(cal -> {
                    GoogleSyncedCalendar stored = storedByGoogleId.get(cal.id());
                    boolean enabled = stored != null && stored.isEnabled();
                    return new GoogleCalendarResponse(cal.id(), cal.name(), cal.primary(), enabled);
                })
                .toList();
    }

    /** Called only after CalendarList discovery has completed every page successfully. */
    @Transactional
    public void disableMissingCalendars(UUID memberId, Set<UUID> candidateIds, Set<String> discoveredIds) {
        for (GoogleSyncedCalendar stored : syncedCalendarRepository.findByMemberIdForUpdate(memberId)) {
            if (candidateIds.contains(stored.getId()) && stored.isEnabled()
                    && !discoveredIds.contains(stored.getGoogleCalendarId())) {
                disableCalendar(stored);
            }
        }
    }

    @Transactional
    public List<GoogleCalendarResponse> updateCalendarSelections(UUID memberId, List<String> calendarIds) {
        GoogleOAuthToken token = tokenRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BadRequestException("Google account not connected"));

        Set<String> selectedIds = new HashSet<>(calendarIds);

        List<GoogleCalendarInfo> googleCalendars = calendarListService.listCalendars(memberId);

        Map<String, GoogleSyncedCalendar> existingByGoogleId = syncedCalendarRepository.findByMemberIdForUpdate(memberId)
                .stream()
                .collect(Collectors.toMap(GoogleSyncedCalendar::getGoogleCalendarId, Function.identity()));

        List<GoogleCalendarResponse> response = new ArrayList<>();

        for (GoogleCalendarInfo cal : googleCalendars) {
            boolean shouldEnable = selectedIds.contains(cal.id());
            GoogleSyncedCalendar existing = existingByGoogleId.get(cal.id());

            if (shouldEnable) {
                if (existing == null) {
                    GoogleSyncedCalendar newCal = new GoogleSyncedCalendar();
                    newCal.setToken(token);
                    newCal.setMember(token.getMember());
                    newCal.setGoogleCalendarId(cal.id());
                    newCal.setCalendarName(cal.name());
                    newCal.setEnabled(true);
                    syncedCalendarRepository.save(newCal);
                } else {
                    if (!existing.isEnabled()) {
                        // A disabled calendar has no imported rows; force a full import.
                        existing.setSyncToken(null);
                    }
                    existing.setEnabled(true);
                    existing.setCalendarName(cal.name());
                    syncedCalendarRepository.save(existing);
                }
            } else if (existing != null && existing.isEnabled()) {
                disableCalendar(existing);
            }

            response.add(new GoogleCalendarResponse(cal.id(), cal.name(), cal.primary(), shouldEnable));
        }

        // A removed subscription is not in the discovery response, so it cannot be
        // handled by the loop above. Discovery is complete before this transaction locks rows.
        Set<String> discoveredIds = googleCalendars.stream().map(GoogleCalendarInfo::id).collect(Collectors.toSet());
        for (GoogleSyncedCalendar stored : existingByGoogleId.values()) {
            if (stored.isEnabled() && !discoveredIds.contains(stored.getGoogleCalendarId())) {
                disableCalendar(stored);
            }
        }

        eventPublisher.publishEvent(new SyncRequestedEvent(memberId));

        return response;
    }

    private void disableCalendar(GoogleSyncedCalendar calendar) {
        calendarEventRepository.deleteBySyncedCalendarAndSource(calendar, EventSource.GOOGLE);
        calendar.setSyncToken(null);
        calendar.setLastSyncedAt(null);
        calendar.setEnabled(false);
        syncedCalendarRepository.save(calendar);
    }
}
