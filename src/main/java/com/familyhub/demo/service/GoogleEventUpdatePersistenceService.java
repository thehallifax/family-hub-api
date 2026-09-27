package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.mapper.CalendarEventMapper;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.EventAudienceType;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
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
public class GoogleEventUpdatePersistenceService {
    private final GoogleSyncedCalendarRepository calendars;
    private final CalendarEventRepository events;
    private final GoogleEventMapper mapper;

    @Transactional
    public CalendarEventResponse persist(Family family, UUID eventId, UUID calendarId,
                                         String googleEventId, Event googleEvent,
                                         EventAudienceType audienceType,
                                         List<FamilyMember> audienceMembers) {
        var calendar = calendars.findByIdForUpdate(calendarId)
                .orElseThrow(() -> new BadRequestException("Google calendar is no longer selected"));
        CalendarEvent row = events.findByFamilyAndId(family, eventId)
                .orElseThrow(() -> new BadRequestException("Google event is no longer available"));
        if (row.getSource() != EventSource.GOOGLE || row.getSyncedCalendar() == null
                || !row.getSyncedCalendar().getId().equals(calendarId)
                || !googleEventId.equals(row.getGoogleEventId())) {
            throw new BadRequestException("Google event linkage changed; use Sync Now before retrying");
        }
        CalendarEvent mapped = mapper.toEntity(googleEvent, calendar);
        row.setTitle(mapped.getTitle());
        row.setDescription(mapped.getDescription());
        row.setLocation(mapped.getLocation());
        row.setHtmlLink(mapped.getHtmlLink());
        row.setEtag(mapped.getEtag());
        row.setGoogleUpdatedAt(mapped.getGoogleUpdatedAt());
        row.setDate(mapped.getDate());
        row.setEndDate(mapped.getEndDate());
        row.setStartTime(mapped.getStartTime());
        row.setEndTime(mapped.getEndTime());
        row.setAllDay(mapped.isAllDay());
        if (audienceType != null) {
            row.setAudienceType(audienceType);
            row.getAudienceMembers().clear();
            row.getAudienceMembers().addAll(audienceMembers);
        }
        return CalendarEventMapper.toDto(events.saveAndFlush(row));
    }
}
