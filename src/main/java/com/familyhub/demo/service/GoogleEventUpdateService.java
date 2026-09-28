package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.dto.GoogleCalendarInfo;
import com.familyhub.demo.dto.GoogleEventEditScope;
import com.familyhub.demo.dto.GoogleEventUpdateRequest;
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
import com.google.api.services.calendar.model.Events;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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

    /** Keeps the established non-recurring service contract for internal callers and tests. */
    public CalendarEventResponse update(Family family, UUID eventId, CalendarEventRequest event) {
        return update(family, eventId, new GoogleEventUpdateRequest(event, null, null));
    }

    public CalendarEventResponse update(Family family, UUID eventId, GoogleEventUpdateRequest request) {
        CalendarEvent local = events.findGoogleEventForWrite(family, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Calendar Event", eventId));
        if (isRecurring(local) && request.scope() == null) {
            throw new BadRequestException(
                    "Recurring Google event edits require an explicit event or series scope");
        }
        GoogleSyncedCalendar calendar = local.getSyncedCalendar();
        UUID memberId = local.getSourceOwnerMember().getId();
        GoogleOAuthToken token = tokens.findByMemberId(memberId)
                .orElseThrow(() -> new BadRequestException("Reconnect this Google account before editing events"));
        validateWritable(family, calendar, memberId, token);
        List<FamilyMember> audience = resolveAudience(family, request.event());
        Calendar client = buildClient(memberId);

        if (!isRecurring(local)) {
            if (request.scope() != null || request.occurrenceDate() != null) {
                throw new BadRequestException("Edit scope is only valid for recurring Google events");
            }
            rejectRecurrenceChange(request.event());
            return updateOrdinary(family, local, request.event(), audience, client);
        }
        return request.scope() == GoogleEventEditScope.THIS_EVENT
                ? updateOccurrence(family, local, request, audience, client)
                : updateSeries(family, local, request.event(), audience, client);
    }

    private CalendarEventResponse updateOrdinary(Family family, CalendarEvent local,
                                                  CalendarEventRequest input,
                                                  List<FamilyMember> audience, Calendar client) {
        GoogleSyncedCalendar calendar = local.getSyncedCalendar();
        Event current = fetch(client, calendar, local.getGoogleEventId(), family, local);
        requireMatchingEtag(local, current, () -> persistence.persist(family, local.getId(), calendar.getId(),
                local.getGoogleEventId(), current, null, List.of()));
        applyOrdinaryFields(current, input, family);
        try {
            Event updated = executeUpdate(client, calendar, local.getGoogleEventId(), current, local.getEtag());
            try {
                return persistence.persist(family, local.getId(), calendar.getId(), local.getGoogleEventId(),
                        updated, input.audienceType(), audience);
            } catch (RuntimeException ex) {
                throw uncertain(ex);
            }
        } catch (GoogleJsonResponseException ex) {
            handleOrdinaryGoogleFailure(ex, client, family, local);
            throw googleRejected();
        } catch (IOException ex) {
            throw googleRejected();
        }
    }

    private CalendarEventResponse updateOccurrence(Family family, CalendarEvent selected,
                                                    GoogleEventUpdateRequest request,
                                                    List<FamilyMember> audience, Calendar client) {
        CalendarEvent parent = recurringParent(family, selected);
        GoogleSyncedCalendar calendar = parent.getSyncedCalendar();
        CalendarEvent localOccurrence = selected.getRecurringEvent() == null ? null : selected;
        Event current;
        if (localOccurrence != null) {
            current = fetch(client, calendar, localOccurrence.getGoogleEventId(), family, localOccurrence);
            requireMatchingEtag(localOccurrence, current, () -> persistence.persistOccurrence(
                    family, localOccurrence.getId(), parent.getId(), calendar.getId(),
                    localOccurrence.getGoogleEventId(), current, null, List.of()));
        } else {
            if (request.occurrenceDate() == null) {
                throw new BadRequestException("The selected occurrence date is required");
            }
            Event googleParent = fetch(client, calendar, parent.getGoogleEventId(), family, parent);
            current = resolveGoogleInstance(client, calendar, parent, googleParent,
                    request.occurrenceDate(), family);
        }

        rejectRecurrenceChange(request.event());
        applyOrdinaryFields(current, request.event(), family);
        String googleInstanceId = current.getId();
        String etag = current.getEtag();
        try {
            Event updated = executeUpdate(client, calendar, googleInstanceId, current, etag);
            try {
                return persistence.persistOccurrence(family,
                        localOccurrence == null ? null : localOccurrence.getId(), parent.getId(),
                        calendar.getId(), googleInstanceId, updated,
                        request.event().audienceType(), audience);
            } catch (RuntimeException ex) {
                throw uncertain(ex);
            }
        } catch (GoogleJsonResponseException ex) {
            if (ex.getStatusCode() == 412) {
                Event latest = fetch(client, calendar, googleInstanceId, family, localOccurrence);
                persistence.persistOccurrence(family,
                        localOccurrence == null ? null : localOccurrence.getId(), parent.getId(),
                        calendar.getId(), googleInstanceId, latest, null, List.of());
                throw conflict();
            }
            if (ex.getStatusCode() == 404 || ex.getStatusCode() == 410) {
                reconcileMissing(family, localOccurrence, calendar, googleInstanceId);
            }
            handlePermissionFailure(ex);
            throw googleRejected();
        } catch (IOException ex) {
            throw googleRejected();
        }
    }

    private CalendarEventResponse updateSeries(Family family, CalendarEvent selected,
                                                CalendarEventRequest input,
                                                List<FamilyMember> audience, Calendar client) {
        CalendarEvent parent = recurringParent(family, selected);
        GoogleSyncedCalendar calendar = parent.getSyncedCalendar();
        Event current = fetch(client, calendar, parent.getGoogleEventId(), family, parent);
        requireMatchingEtag(parent, current, () -> persistence.persistSeries(family, parent.getId(),
                calendar.getId(), parent.getGoogleEventId(), current, null, List.of()));

        // Timing and the complete recurrence array remain Google-authoritative for series edits.
        current.setSummary(input.title());
        current.setDescription(input.description());
        current.setLocation(input.location());
        try {
            Event updated = executeUpdate(client, calendar, parent.getGoogleEventId(), current, parent.getEtag());
            try {
                return persistence.persistSeries(family, parent.getId(), calendar.getId(),
                        parent.getGoogleEventId(), updated, input.audienceType(), audience);
            } catch (RuntimeException ex) {
                throw uncertain(ex);
            }
        } catch (GoogleJsonResponseException ex) {
            if (ex.getStatusCode() == 412) {
                Event latest = fetch(client, calendar, parent.getGoogleEventId(), family, parent);
                persistence.persistSeries(family, parent.getId(), calendar.getId(),
                        parent.getGoogleEventId(), latest, null, List.of());
                throw conflict();
            }
            if (ex.getStatusCode() == 404 || ex.getStatusCode() == 410) {
                reconcileMissing(family, parent, calendar, parent.getGoogleEventId());
            }
            handlePermissionFailure(ex);
            throw googleRejected();
        } catch (IOException ex) {
            throw googleRejected();
        }
    }

    private Event resolveGoogleInstance(Calendar client, GoogleSyncedCalendar calendar,
                                        CalendarEvent parent, Event googleParent,
                                        LocalDate occurrenceDate, Family family) {
        String originalStart = originalStart(parent, googleParent, occurrenceDate, family.getTimezone());
        try {
            Events instances = client.events().instances(calendar.getGoogleCalendarId(), parent.getGoogleEventId())
                    .setOriginalStart(originalStart).setShowDeleted(true).setMaxResults(2).execute();
            List<Event> matches = instances.getItems() == null ? List.of() : instances.getItems().stream()
                    .filter(event -> parent.getGoogleEventId().equals(event.getRecurringEventId()))
                    .filter(event -> !"cancelled".equals(event.getStatus())).toList();
            if (matches.size() != 1 || matches.getFirst().getId() == null) {
                throw new ResourceNotFoundException("Google Calendar occurrence", parent.getId());
            }
            return matches.getFirst();
        } catch (GoogleJsonResponseException ex) {
            if (ex.getStatusCode() == 404 || ex.getStatusCode() == 410) {
                reconcileMissing(family, parent, calendar, parent.getGoogleEventId());
            }
            throw new BadRequestException("Google could not resolve this occurrence. The FamilyHub event was kept.");
        } catch (IOException ex) {
            throw new BadRequestException("Google could not resolve this occurrence. The FamilyHub event was kept.");
        }
    }

    private String originalStart(CalendarEvent parent, Event googleParent, LocalDate occurrenceDate,
                                 String familyTimezone) {
        ZoneId zone = ZoneId.of(familyTimezone);
        if (!parent.isAllDay() && googleParent.getStart() != null) {
            if (googleParent.getStart().getTimeZone() != null) {
                zone = ZoneId.of(googleParent.getStart().getTimeZone());
            } else if (googleParent.getStart().getDateTime() != null) {
                zone = ZoneOffset.ofTotalSeconds(
                        googleParent.getStart().getDateTime().getTimeZoneShift() * 60);
            }
        }
        LocalTime time = parent.isAllDay() ? LocalTime.MIDNIGHT : parent.getStartTime();
        return ZonedDateTime.of(occurrenceDate, time, zone)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private CalendarEvent recurringParent(Family family, CalendarEvent selected) {
        UUID parentId = selected.getRecurringEvent() == null
                ? selected.getId() : selected.getRecurringEvent().getId();
        CalendarEvent parent = events.findGoogleEventForWrite(family, parentId)
                .orElseThrow(() -> new ResourceNotFoundException("Calendar Event", parentId));
        if (parent.getRecurrenceRule() == null || parent.getRecurringEvent() != null
                || parent.getSyncedCalendar() == null
                || !parent.getSyncedCalendar().getId().equals(selected.getSyncedCalendar().getId())) {
            throw new BadRequestException("Google recurring series linkage is invalid; use Sync Now before retrying");
        }
        return parent;
    }

    private boolean isRecurring(CalendarEvent event) {
        return event.getRecurrenceRule() != null || event.getRecurringEvent() != null
                || event.getOriginalDate() != null;
    }

    private void rejectRecurrenceChange(CalendarEventRequest input) {
        if (input.recurrenceRule() != null && !input.recurrenceRule().isBlank()) {
            throw new BadRequestException("Google recurrence rules cannot be changed in FamilyHub");
        }
    }

    private void applyOrdinaryFields(Event current, CalendarEventRequest input, Family family) {
        Event desired = GoogleEventCreationService.toGoogleEvent(input, family.getTimezone(), UUID.randomUUID());
        current.setSummary(desired.getSummary());
        current.setDescription(desired.getDescription());
        current.setLocation(desired.getLocation());
        current.setStart(desired.getStart());
        current.setEnd(desired.getEnd());
    }

    private Event executeUpdate(Calendar client, GoogleSyncedCalendar calendar, String googleEventId,
                                Event event, String etag) throws IOException {
        Calendar.Events.Update update = client.events().update(calendar.getGoogleCalendarId(), googleEventId, event);
        HttpHeaders headers = update.getRequestHeaders();
        headers.setIfMatch(etag);
        return update.execute();
    }

    private Event fetch(Calendar client, GoogleSyncedCalendar calendar, String googleEventId,
                        Family family, CalendarEvent local) {
        try {
            return client.events().get(calendar.getGoogleCalendarId(), googleEventId).execute();
        } catch (GoogleJsonResponseException ex) {
            if (ex.getStatusCode() == 404 || ex.getStatusCode() == 410) {
                reconcileMissing(family, local, calendar, googleEventId);
            }
            throw new BadRequestException("Google could not load the current event. The FamilyHub event was kept.");
        } catch (IOException ex) {
            throw new BadRequestException("Google could not load the current event. The FamilyHub event was kept.");
        }
    }

    private void requireMatchingEtag(CalendarEvent local, Event current, Runnable reconcile) {
        if (local.getEtag() == null || !local.getEtag().equals(current.getEtag())) {
            reconcile.run();
            throw conflict();
        }
    }

    private void handleOrdinaryGoogleFailure(GoogleJsonResponseException ex, Calendar client,
                                             Family family, CalendarEvent local) {
        if (ex.getStatusCode() == 412) {
            Event latest = fetch(client, local.getSyncedCalendar(), local.getGoogleEventId(), family, local);
            persistence.persist(family, local.getId(), local.getSyncedCalendar().getId(),
                    local.getGoogleEventId(), latest, null, List.of());
            throw conflict();
        }
        if (ex.getStatusCode() == 404 || ex.getStatusCode() == 410) {
            reconcileMissing(family, local, local.getSyncedCalendar(), local.getGoogleEventId());
        }
        handlePermissionFailure(ex);
    }

    private void reconcileMissing(Family family, CalendarEvent local,
                                  GoogleSyncedCalendar calendar, String googleEventId) {
        if (local != null) {
            deletionPersistence.reconcile(family, local.getId(), calendar.getId(), googleEventId);
        }
        throw new ResourceNotFoundException("Google Calendar Event",
                local == null ? UUID.randomUUID() : local.getId());
    }

    private void handlePermissionFailure(GoogleJsonResponseException ex) {
        if (ex.getStatusCode() == 401 || ex.getStatusCode() == 403) {
            throw new BadRequestException("Google denied event editing. Reconnect or check calendar permissions.");
        }
    }

    private ConflictException conflict() {
        return new ConflictException(
                "This event changed in Google Calendar. It has been refreshed; review it before editing again.");
    }

    private BadRequestException googleRejected() {
        return new BadRequestException("Google did not confirm the event update. The FamilyHub event was kept.");
    }

    private GoogleWriteUncertainException uncertain(RuntimeException cause) {
        return new GoogleWriteUncertainException(
                "Google updated the event, but FamilyHub could not save its local copy. Use Sync Now to reconcile it.",
                cause);
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
        if (result.size() != ids.size()
                || result.stream().anyMatch(member -> !member.getFamily().getId().equals(family.getId()))) {
            throw new BadRequestException("Audience contains a member outside this household");
        }
        return result;
    }

    Calendar buildClient(UUID memberId) {
        return new Calendar.Builder(credentials.getHttpTransport(), credentials.getJsonFactory(),
                credentials.getCredential(memberId)).setApplicationName("FamilyHub").build();
    }
}
