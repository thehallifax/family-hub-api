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
public class GoogleEventUpdatePersistenceService {
    private final GoogleSyncedCalendarRepository calendars;
    private final CalendarEventRepository events;
    private final GoogleEventMapper mapper;

    @Transactional
    public CalendarEventResponse persist(Family family, UUID eventId, UUID calendarId,
                                         String googleEventId, Event googleEvent,
                                         EventAudienceType audienceType,
                                         List<FamilyMember> audienceMembers) {
        var calendar = requireActiveCalendar(family, calendarId);
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

    @Transactional
    public CalendarEventResponse persistOccurrence(Family family, UUID localEventId,
                                                    UUID parentId, UUID calendarId,
                                                    String googleEventId, Event googleEvent,
                                                    EventAudienceType audienceType,
                                                    List<FamilyMember> audienceMembers) {
        var calendar = requireActiveCalendar(family, calendarId);
        CalendarEvent parent = events.findByFamilyAndId(family, parentId)
                .orElseThrow(() -> new BadRequestException("Google recurring series is no longer available"));
        requireGoogleLink(parent, calendarId, googleEvent.getRecurringEventId());

        CalendarEvent row = events.findBySyncedCalendarAndSourceAndGoogleEventId(
                        calendar, EventSource.GOOGLE, googleEventId)
                .orElse(null);
        if (localEventId != null && (row == null || !localEventId.equals(row.getId()))) {
            throw new BadRequestException("Google occurrence linkage changed; use Sync Now before retrying");
        }

        CalendarEvent mapped = mapper.toExceptionEntity(googleEvent, calendar, parent);
        if (row == null) {
            row = mapped;
            inheritAudience(parent, row);
        } else {
            requireGoogleLink(row, calendarId, googleEventId);
            copyGoogleFields(mapped, row);
        }
        if (audienceType != null) {
            setAudience(row, audienceType, audienceMembers);
            row.setGoogleAudienceOverride(true);
        }
        return CalendarEventMapper.toDto(events.saveAndFlush(row));
    }

    @Transactional
    public CalendarEventResponse persistSeries(Family family, UUID parentId, UUID calendarId,
                                                String googleEventId, Event googleEvent,
                                                EventAudienceType audienceType,
                                                List<FamilyMember> audienceMembers) {
        var calendar = requireActiveCalendar(family, calendarId);
        CalendarEvent parent = events.findByFamilyAndId(family, parentId)
                .orElseThrow(() -> new BadRequestException("Google recurring series is no longer available"));
        requireGoogleLink(parent, calendarId, googleEventId);

        CalendarEvent mapped = mapper.toEntity(googleEvent, calendar);
        copyGoogleFields(mapped, parent);
        if (audienceType != null) {
            setAudience(parent, audienceType, audienceMembers);
            for (CalendarEvent exception : events.findByRecurringEvent(parent)) {
                if (!exception.isGoogleAudienceOverride()) {
                    inheritAudience(parent, exception);
                }
            }
        }
        return CalendarEventMapper.toDto(events.saveAndFlush(parent));
    }

    private void requireGoogleLink(CalendarEvent row, UUID calendarId, String googleEventId) {
        if (row.getSource() != EventSource.GOOGLE || row.getSyncedCalendar() == null
                || !row.getSyncedCalendar().getId().equals(calendarId)
                || !googleEventId.equals(row.getGoogleEventId())) {
            throw new BadRequestException("Google event linkage changed; use Sync Now before retrying");
        }
    }

    private GoogleSyncedCalendar requireActiveCalendar(Family family, UUID calendarId) {
        GoogleSyncedCalendar calendar = calendars.findByIdForUpdate(calendarId)
                .orElseThrow(() -> new BadRequestException("Google calendar is no longer selected"));
        if (!calendar.isEnabled() || calendar.getMember() == null
                || calendar.getMember().getFamily() == null
                || !calendar.getMember().getFamily().getId().equals(family.getId())) {
            throw new BadRequestException("Google calendar is no longer selected");
        }
        return calendar;
    }

    private void copyGoogleFields(CalendarEvent mapped, CalendarEvent row) {
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
        row.setRecurrenceRule(mapped.getRecurrenceRule());
        row.setExdates(mapped.getExdates());
        row.setCancelled(mapped.isCancelled());
    }

    private void setAudience(CalendarEvent event, EventAudienceType type,
                             List<FamilyMember> audienceMembers) {
        event.setAudienceType(type);
        event.getAudienceMembers().clear();
        event.getAudienceMembers().addAll(audienceMembers);
    }

    private void inheritAudience(CalendarEvent parent, CalendarEvent occurrence) {
        setAudience(occurrence, parent.getAudienceType(), List.copyOf(parent.getAudienceMembers()));
        occurrence.setGoogleAudienceOverride(false);
    }
}
