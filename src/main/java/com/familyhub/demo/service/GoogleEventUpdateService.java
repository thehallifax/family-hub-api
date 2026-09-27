package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.dto.GoogleCalendarInfo;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ConflictException;
import com.familyhub.demo.exception.GoogleWriteUncertainException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.EventAudienceType;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GoogleEventUpdateService {
    private final CalendarEventRepository events;
    private final FamilyMemberRepository members;
    private final GoogleOAuthTokenRepository tokens;
    private final GoogleCalendarListService discovery;
    private final GoogleCredentialService credentials;
    private final GoogleEventUpdatePersistenceService persistence;
    private final GoogleEventDeletionPersistenceService deletionPersistence;

    public CalendarEventResponse update(Family family, UUID eventId, CalendarEventRequest input) {
        CalendarEvent local = events.findGoogleEventForWrite(family, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Calendar Event", eventId));
        rejectUnsupported(local, input);
        GoogleSyncedCalendar calendar = local.getSyncedCalendar();
        UUID memberId = local.getSourceOwnerMember().getId();
        GoogleOAuthToken token = tokens.findByMemberId(memberId)
                .orElseThrow(() -> new BadRequestException("Reconnect this Google account before editing events"));
        validateWritable(family, calendar, memberId, token);
        List<FamilyMember> audience = resolveAudience(family, input);
        Calendar client = buildClient(memberId);
        Event current = fetch(client, calendar, local, family, eventId);
        if (local.getEtag() == null || !local.getEtag().equals(current.getEtag())) {
            reconcileConflict(family, eventId, calendar, local, current);
        }

        Event desired = GoogleEventCreationService.toGoogleEvent(input, family.getTimezone(), UUID.randomUUID());
        current.setSummary(desired.getSummary());
        current.setDescription(desired.getDescription());
        current.setLocation(desired.getLocation());
        current.setStart(desired.getStart());
        current.setEnd(desired.getEnd());
        try {
            Calendar.Events.Update request = client.events().update(
                    calendar.getGoogleCalendarId(), local.getGoogleEventId(), current);
            HttpHeaders headers = request.getRequestHeaders();
            headers.setIfMatch(local.getEtag());
            Event updated = request.execute();
            try {
                return persistence.persist(family, eventId, calendar.getId(), local.getGoogleEventId(),
                        updated, input.audienceType(), audience);
            } catch (RuntimeException ex) {
                throw new GoogleWriteUncertainException(
                        "Google updated the event, but FamilyHub could not save its local copy. Use Sync Now to reconcile it.", ex);
            }
        } catch (GoogleJsonResponseException ex) {
            if (ex.getStatusCode() == 412) {
                Event latest = fetch(client, calendar, local, family, eventId);
                reconcileConflict(family, eventId, calendar, local, latest);
            }
            if (ex.getStatusCode() == 404 || ex.getStatusCode() == 410) {
                reconcileMissing(family, eventId, calendar, local);
            }
            if (ex.getStatusCode() == 401 || ex.getStatusCode() == 403) {
                throw new BadRequestException("Google denied event editing. Reconnect or check calendar permissions.");
            }
            throw new BadRequestException("Google did not confirm the event update. The FamilyHub event was kept.");
        } catch (IOException ex) {
            throw new BadRequestException("Google did not confirm the event update. The FamilyHub event was kept.");
        }
    }

    private Event fetch(Calendar client, GoogleSyncedCalendar calendar, CalendarEvent local,
                        Family family, UUID eventId) {
        try {
            return client.events().get(calendar.getGoogleCalendarId(), local.getGoogleEventId()).execute();
        } catch (GoogleJsonResponseException ex) {
            if (ex.getStatusCode() == 404 || ex.getStatusCode() == 410) {
                reconcileMissing(family, eventId, calendar, local);
            }
            throw new BadRequestException("Google could not load the current event. The FamilyHub event was kept.");
        } catch (IOException ex) {
            throw new BadRequestException("Google could not load the current event. The FamilyHub event was kept.");
        }
    }

    private void reconcileConflict(Family family, UUID eventId, GoogleSyncedCalendar calendar,
                                   CalendarEvent local, Event current) {
        persistence.persist(family, eventId, calendar.getId(), local.getGoogleEventId(),
                current, null, List.of());
        throw new ConflictException("This event changed in Google Calendar. It has been refreshed; review it before editing again.");
    }

    private void reconcileMissing(Family family, UUID eventId, GoogleSyncedCalendar calendar,
                                  CalendarEvent local) {
        deletionPersistence.reconcile(family, eventId, calendar.getId(), local.getGoogleEventId());
        throw new ResourceNotFoundException("Google Calendar Event", eventId);
    }

    private void rejectUnsupported(CalendarEvent event, CalendarEventRequest input) {
        if (event.getRecurrenceRule() != null || event.getRecurringEvent() != null || event.getOriginalDate() != null) {
            throw new BadRequestException("Recurring Google events cannot be edited in FamilyHub yet");
        }
        if (input.recurrenceRule() != null && !input.recurrenceRule().isBlank()) {
            throw new BadRequestException("Recurring Google event editing is not supported yet");
        }
    }

    private void validateWritable(Family family, GoogleSyncedCalendar calendar, UUID memberId,
                                  GoogleOAuthToken token) {
        if (calendar == null || !calendar.isEnabled() || !calendar.getMember().getId().equals(memberId)
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
        if (!writable) throw new BadRequestException("This Google calendar is read-only or unavailable");
    }

    private List<FamilyMember> resolveAudience(Family family, CalendarEventRequest input) {
        if (input.audienceType() == EventAudienceType.FAMILY) return List.of();
        if (input.memberIds() == null || input.memberIds().isEmpty()) {
            throw new BadRequestException("Choose at least one household member");
        }
        var ids = new LinkedHashSet<>(input.memberIds());
        List<FamilyMember> result = members.findAllById(ids);
        if (result.size() != ids.size() || result.stream().anyMatch(m -> !m.getFamily().getId().equals(family.getId()))) {
            throw new BadRequestException("Audience contains a member outside this household");
        }
        return result;
    }

    Calendar buildClient(UUID memberId) {
        return new Calendar.Builder(credentials.getHttpTransport(), credentials.getJsonFactory(),
                credentials.getCredential(memberId)).setApplicationName("FamilyHub").build();
    }
}
