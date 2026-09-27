package com.familyhub.demo.service;

import com.familyhub.demo.dto.GoogleCalendarInfo;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.GoogleWriteUncertainException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.calendar.Calendar;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GoogleEventDeletionService {
    private final CalendarEventRepository events;
    private final GoogleOAuthTokenRepository tokens;
    private final GoogleCalendarListService discovery;
    private final GoogleCredentialService credentials;
    private final GoogleEventDeletionPersistenceService persistence;

    public void delete(Family family, UUID eventId) {
        CalendarEvent event = events.findGoogleEventForWrite(family, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Calendar Event", eventId));
        if (event.getRecurrenceRule() != null || event.getRecurringEvent() != null || event.getOriginalDate() != null) {
            throw new BadRequestException("Recurring Google events cannot be deleted in FamilyHub yet");
        }
        GoogleSyncedCalendar calendar = event.getSyncedCalendar();
        if (calendar == null || event.getGoogleEventId() == null || event.getSourceOwnerMember() == null) {
            throw new BadRequestException("Google event linkage is incomplete; use Sync Now before retrying");
        }
        UUID memberId = event.getSourceOwnerMember().getId();
        GoogleOAuthToken token = tokens.findByMemberId(memberId)
                .orElseThrow(() -> new BadRequestException("Reconnect this Google account before deleting events"));
        if (!calendar.isEnabled()
                || !calendar.getMember().getId().equals(memberId)
                || !calendar.getMember().getFamily().getId().equals(family.getId())
                || !calendar.getToken().getId().equals(token.getId())
                || !GoogleOAuthService.hasWriteScope(token.getScope())) {
            throw new BadRequestException("This Google calendar is no longer writable; reconnect or check its selection");
        }
        boolean writable;
        try {
            writable = discovery.listCalendars(memberId).stream()
                    .filter(info -> info.id().equals(calendar.getGoogleCalendarId()))
                    .anyMatch(GoogleCalendarInfo::writable);
        } catch (RuntimeException ex) {
            throw new BadRequestException("Could not verify Google calendar access. Retry shortly.");
        }
        if (!writable) {
            throw new BadRequestException("This Google calendar is read-only or unavailable");
        }

        try {
            buildClient(memberId).events()
                    .delete(calendar.getGoogleCalendarId(), event.getGoogleEventId())
                    .execute();
        } catch (GoogleJsonResponseException ex) {
            if (ex.getStatusCode() != 404 && ex.getStatusCode() != 410) {
                if (ex.getStatusCode() == 401 || ex.getStatusCode() == 403) {
                    throw new BadRequestException("Google denied event deletion. Reconnect or check calendar permissions.");
                }
                throw new BadRequestException("Google did not confirm event deletion. The FamilyHub event was kept.");
            }
            // Already absent is an authoritative successful reconciliation.
        } catch (IOException ex) {
            throw new BadRequestException("Google did not confirm event deletion. The FamilyHub event was kept.");
        }

        try {
            persistence.reconcile(family, eventId, calendar.getId(), event.getGoogleEventId());
        } catch (RuntimeException ex) {
            throw new GoogleWriteUncertainException(
                    "Google deleted the event, but FamilyHub could not remove its local copy. Use Sync Now to reconcile it.", ex);
        }
    }

    Calendar buildClient(UUID memberId) {
        return new Calendar.Builder(credentials.getHttpTransport(), credentials.getJsonFactory(),
                credentials.getCredential(memberId)).setApplicationName("FamilyHub").build();
    }
}
