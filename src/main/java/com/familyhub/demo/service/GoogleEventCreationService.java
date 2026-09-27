package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.dto.GoogleCalendarInfo;
import com.familyhub.demo.dto.GoogleEventCreateRequest;
import com.familyhub.demo.dto.GoogleWriteDestination;
import com.familyhub.demo.dto.GoogleWriteDestinations;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.GoogleWriteUncertainException;
import com.familyhub.demo.mapper.CalendarEventMapper;
import com.familyhub.demo.model.EventAudienceType;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.repository.GoogleSyncedCalendarRepository;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GoogleEventCreationService {
    private static final DateTimeFormatter GOOGLE_DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");
    private final FamilyMemberRepository members;
    private final GoogleOAuthTokenRepository tokens;
    private final GoogleSyncedCalendarRepository calendars;
    private final GoogleCalendarListService discovery;
    private final GoogleCredentialService credentials;
    private final GoogleEventPersistenceService persistence;

    public GoogleWriteDestinations destinations(Family family) {
        List<GoogleWriteDestination> destinations = new ArrayList<>();
        List<UUID> reconnect = new ArrayList<>();
        List<UUID> unavailable = new ArrayList<>();
        for (FamilyMember member : members.findByFamily(family)) {
            GoogleOAuthToken token = tokens.findByMemberId(member.getId()).orElse(null);
            if (token == null) continue;
            if (!GoogleOAuthService.hasWriteScope(token.getScope())) {
                reconnect.add(member.getId());
                continue;
            }
            try {
                List<GoogleCalendarInfo> current = discovery.listCalendars(member.getId());
                for (GoogleSyncedCalendar selected : calendars.findByMemberIdAndEnabledTrue(member.getId())) {
                    current.stream().filter(info -> info.id().equals(selected.getGoogleCalendarId()) && info.writable())
                            .findFirst().ifPresent(info -> destinations.add(new GoogleWriteDestination(
                                    selected.getId(), member.getId(), member.getName(), info.name(), info.accessRole())));
                }
            } catch (RuntimeException ex) {
                unavailable.add(member.getId());
            }
        }
        return new GoogleWriteDestinations(destinations, reconnect, unavailable);
    }

    public CalendarEventResponse create(Family family, GoogleEventCreateRequest request) {
        FamilyMember owner = members.findById(request.sourceOwnerMemberId())
                .orElseThrow(() -> new BadRequestException("Google calendar is not available to this household"));
        if (!owner.getFamily().getId().equals(family.getId())) {
            throw new BadRequestException("Google calendar is not available to this household");
        }
        GoogleSyncedCalendar selected = calendars.findById(request.syncedCalendarId())
                .orElseThrow(() -> new BadRequestException("Google calendar is not selected"));
        GoogleOAuthToken token = tokens.findByMemberId(owner.getId())
                .orElseThrow(() -> new BadRequestException("Reconnect this Google account before creating events"));
        if (!selected.isEnabled() || !selected.getMember().getId().equals(owner.getId())
                || !selected.getToken().getId().equals(token.getId())
                || !selected.getMember().getFamily().getId().equals(family.getId())) {
            throw new BadRequestException("Google calendar is not selected for this member");
        }
        if (!GoogleOAuthService.hasWriteScope(token.getScope())) {
            throw new BadRequestException("Reconnect this Google account to grant event write permission");
        }
        boolean writable;
        try {
            writable = discovery.listCalendars(owner.getId()).stream()
                    .anyMatch(info -> info.id().equals(selected.getGoogleCalendarId()) && info.writable());
        } catch (RuntimeException ex) {
            throw new BadRequestException("Could not verify Google calendar access. Retry shortly.");
        }
        if (!writable) throw new BadRequestException("This Google calendar is read-only or unavailable");

        CalendarEventRequest input = request.event();
        if (input.recurrenceRule() != null && !input.recurrenceRule().isBlank()) {
            throw new BadRequestException("Recurring Google event creation is not supported yet");
        }
        List<FamilyMember> audience = resolveAudience(family, input);
        Event proposed = toGoogleEvent(input, family.getTimezone(), request.clientRequestId());
        Calendar client = buildClient(owner.getId());
        Event created;
        try {
            created = client.events().insert(selected.getGoogleCalendarId(), proposed).execute();
        } catch (GoogleJsonResponseException ex) {
            if (ex.getStatusCode() == 409) {
                // Same client request retried after an uncertain result. Import the
                // existing deterministic Google identity instead of inserting again.
                try {
                    created = client.events().get(selected.getGoogleCalendarId(), proposed.getId()).execute();
                } catch (IOException fetchFailure) {
                    throw new BadRequestException("Google may have created the event. Use Sync Now before trying again.");
                }
            } else if (ex.getStatusCode() == 401 || ex.getStatusCode() == 403) {
                throw new BadRequestException("Google denied event creation. Reconnect or check calendar permissions.");
            } else {
                throw new BadRequestException("Google did not confirm event creation. Check Google Calendar before retrying.");
            }
        } catch (IOException ex) {
            throw new BadRequestException("Google did not confirm event creation. Check Google Calendar before retrying.");
        }
        try {
            return persistence.persist(selected.getId(), owner.getId(), family, created, input.audienceType(), audience);
        } catch (RuntimeException ex) {
            throw new GoogleWriteUncertainException(ex);
        }
    }

    Calendar buildClient(UUID memberId) {
        return new Calendar.Builder(credentials.getHttpTransport(), credentials.getJsonFactory(),
                credentials.getCredential(memberId)).setApplicationName("FamilyHub").build();
    }

    private List<FamilyMember> resolveAudience(Family family, CalendarEventRequest request) {
        if (request.audienceType() == EventAudienceType.FAMILY) return List.of();
        if (request.memberIds() == null || request.memberIds().isEmpty()) {
            throw new BadRequestException("Choose at least one household member");
        }
        var ids = new LinkedHashSet<>(request.memberIds());
        List<FamilyMember> result = members.findAllById(ids);
        if (result.size() != ids.size() || result.stream().anyMatch(m -> !m.getFamily().getId().equals(family.getId()))) {
            throw new BadRequestException("Audience contains a member outside this household");
        }
        return result;
    }

    static Event toGoogleEvent(CalendarEventRequest input, String timezone, UUID requestId) {
        if (input.endDate() != null && input.endDate().isBefore(input.date())) {
            throw new BadRequestException("End date must not precede start date");
        }
        Event event = new Event().setId("fh" + requestId.toString().replace("-", ""))
                .setSummary(input.title()).setDescription(input.description()).setLocation(input.location());
        if (Boolean.TRUE.equals(input.isAllDay())) {
            LocalDate end = input.endDate() == null ? input.date() : input.endDate();
            return event.setStart(new EventDateTime().setDate(new DateTime(input.date().toString())))
                    .setEnd(new EventDateTime().setDate(new DateTime(end.plusDays(1).toString())));
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (RuntimeException ex) {
            throw new BadRequestException("Household timezone is not configured correctly");
        }
        LocalTime start = CalendarEventMapper.parseTime(input.startTime());
        LocalTime end = CalendarEventMapper.parseTime(input.endTime());
        LocalDate endDate = input.endDate() == null ? input.date() : input.endDate();
        ZonedDateTime startAt = validZoned(input.date(), start, zone);
        ZonedDateTime endAt = validZoned(endDate, end, zone);
        if (!endAt.toInstant().isAfter(startAt.toInstant())) {
            throw new BadRequestException("End time must be after start time");
        }
        return event.setStart(new EventDateTime()
                        .setDateTime(new DateTime(GOOGLE_DATE_TIME.format(startAt))).setTimeZone(zone.getId()))
                .setEnd(new EventDateTime()
                        .setDateTime(new DateTime(GOOGLE_DATE_TIME.format(endAt))).setTimeZone(zone.getId()));
    }

    private static ZonedDateTime validZoned(LocalDate date, LocalTime time, ZoneId zone) {
        LocalDateTime local = LocalDateTime.of(date, time);
        if (zone.getRules().getValidOffsets(local).isEmpty()) {
            throw new BadRequestException("This time does not exist in the household timezone");
        }
        return ZonedDateTime.of(local, zone);
    }
}
