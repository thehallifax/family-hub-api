package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.mapper.CalendarEventMapper;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.EventAudienceType;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.GoogleSyncedCalendarRepository;
import com.google.api.services.calendar.model.Event;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GoogleEventPersistenceService {
    private final GoogleSyncedCalendarRepository calendars;
    private final CalendarEventRepository events;
    private final GoogleEventMapper mapper;

    @Transactional
    public CalendarEventResponse persist(UUID calendarId, UUID memberId, Family family, Event googleEvent,
                                         EventAudienceType audienceType, List<FamilyMember> audienceMembers) {
        GoogleSyncedCalendar calendar = calendars.findByIdForUpdate(calendarId)
                .orElseThrow(() -> new BadRequestException("Google calendar is no longer selected"));
        if (!calendar.isEnabled() || !calendar.getMember().getId().equals(memberId)
                || !calendar.getMember().getFamily().getId().equals(family.getId())
                || !calendar.getToken().getMember().getId().equals(memberId)
                || !GoogleOAuthService.hasWriteScope(calendar.getToken().getScope())) {
            throw new BadRequestException("Google calendar is no longer writable; use Sync Now to reconcile the event");
        }
        CalendarEvent row = events.findBySyncedCalendarAndSourceAndGoogleEventId(
                        calendar, EventSource.GOOGLE, googleEvent.getId())
                .orElseGet(() -> mapper.toEntity(googleEvent, calendar));
        // A concurrent sync may already have imported the Google-created event.
        row.setAudienceType(audienceType);
        row.getAudienceMembers().clear();
        row.getAudienceMembers().addAll(audienceMembers);
        return CalendarEventMapper.toDto(events.saveAndFlush(row));
    }
}
