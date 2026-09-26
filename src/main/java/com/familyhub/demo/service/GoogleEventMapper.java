package com.familyhub.demo.service;

import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.EventAudienceType;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

@Component
public class GoogleEventMapper {

    public CalendarEvent toEntity(Event googleEvent, GoogleSyncedCalendar syncedCal) {
        CalendarEvent entity = new CalendarEvent();
        mapCommonFields(googleEvent, syncedCal, entity);
        mapDateAndTime(googleEvent, entity);

        if (googleEvent.getRecurrence() != null && !googleEvent.getRecurrence().isEmpty()) {
            String rrule = googleEvent.getRecurrence().stream()
                    .filter(r -> r.startsWith("RRULE:"))
                    .findFirst()
                    .map(r -> r.substring("RRULE:".length()))
                    .orElse(null);
            entity.setRecurrenceRule(rrule);

            String exdates = googleEvent.getRecurrence().stream()
                    .filter(r -> r.startsWith("EXDATE"))
                    .map(this::parseExdateEntry)
                    .collect(Collectors.joining(","));
            if (!exdates.isEmpty()) {
                entity.setExdates(exdates);
            }
        }

        return entity;
    }

    /**
     * Parses a single EXDATE entry from Google's recurrence list into an ISO date string.
     * Handles formats: "EXDATE;VALUE=DATE:20250617", "EXDATE:20250617T130000Z",
     * "EXDATE;TZID=America/New_York:20250617T090000"
     */
    private String parseExdateEntry(String exdate) {
        String value = exdate.substring(exdate.lastIndexOf(':') + 1);
        String dateStr = value.substring(0, 8);
        return LocalDate.parse(dateStr, DateTimeFormatter.BASIC_ISO_DATE).toString();
    }

    public CalendarEvent toExceptionEntity(Event googleEvent, GoogleSyncedCalendar syncedCal,
                                            CalendarEvent parentEntity) {
        CalendarEvent entity = new CalendarEvent();
        mapCommonFields(googleEvent, syncedCal, entity);

        // Cancelled events may lack start/end — use parent's time fields as fallback
        if (googleEvent.getStart() != null && googleEvent.getEnd() != null) {
            mapDateAndTime(googleEvent, entity);
        } else {
            // Fallback for cancelled exceptions: use originalDate + parent's times
            entity.setDate(parseOriginalDate(googleEvent));
            entity.setStartTime(parentEntity.getStartTime());
            entity.setEndTime(parentEntity.getEndTime());
            entity.setAllDay(parentEntity.isAllDay());
        }

        entity.setRecurringEvent(parentEntity);
        entity.setOriginalDate(parseOriginalDate(googleEvent));
        entity.setCancelled("cancelled".equals(googleEvent.getStatus()));

        return entity;
    }

    private void mapCommonFields(Event googleEvent, GoogleSyncedCalendar syncedCal,
                                  CalendarEvent entity) {
        String title = googleEvent.getSummary();
        entity.setTitle(title != null ? title : "(No title)");
        entity.setDescription(googleEvent.getDescription());
        entity.setLocation(googleEvent.getLocation());
        entity.setHtmlLink(googleEvent.getHtmlLink());
        entity.setGoogleEventId(googleEvent.getId());
        entity.setEtag(googleEvent.getEtag());
        entity.setSource(EventSource.GOOGLE);
        entity.setAudienceType(EventAudienceType.MEMBERS);
        entity.getAudienceMembers().add(syncedCal.getMember());
        entity.setSourceOwnerMember(syncedCal.getMember());
        entity.setFamily(syncedCal.getMember().getFamily());
        entity.setSyncedCalendar(syncedCal);

        if (googleEvent.getUpdated() != null) {
            entity.setGoogleUpdatedAt(Instant.ofEpochMilli(googleEvent.getUpdated().getValue()));
        }
    }

    private void mapDateAndTime(Event googleEvent, CalendarEvent entity) {
        EventDateTime start = googleEvent.getStart();
        EventDateTime end = googleEvent.getEnd();

        if (start.getDate() != null) {
            // All-day event
            entity.setAllDay(true);
            LocalDate startDate = parseLocalDate(start.getDate());
            entity.setDate(startDate);
            entity.setStartTime(LocalTime.MIDNIGHT);
            entity.setEndTime(LocalTime.MIDNIGHT);

            // Google end date is exclusive, ours is inclusive
            LocalDate googleEndDate = parseLocalDate(end.getDate());
            LocalDate inclusiveEndDate = googleEndDate.minusDays(1);
            if (inclusiveEndDate.isAfter(startDate)) {
                entity.setEndDate(inclusiveEndDate);
            }
            // Single-day: endDate stays null
        } else {
            // Timed event
            // TODO: Events crossing midnight (e.g., 11 PM → 1 AM) will have endTime < startTime
            // with no endDate set. This may confuse query/rendering. Rare edge case for Phase 1.
            entity.setAllDay(false);
            ZonedDateTime startZdt = toZonedDateTime(start.getDateTime());
            ZonedDateTime endZdt = toZonedDateTime(end.getDateTime());
            entity.setDate(startZdt.toLocalDate());
            entity.setStartTime(startZdt.toLocalTime());
            entity.setEndTime(endZdt.toLocalTime());
        }
    }

    private LocalDate parseOriginalDate(Event googleEvent) {
        EventDateTime original = googleEvent.getOriginalStartTime();
        if (original.getDate() != null) {
            return parseLocalDate(original.getDate());
        }
        return toZonedDateTime(original.getDateTime()).toLocalDate();
    }

    private LocalDate parseLocalDate(DateTime googleDate) {
        return LocalDate.parse(googleDate.toStringRfc3339());
    }

    /**
     * Converts a Google DateTime to ZonedDateTime using the event's original timezone offset.
     * This means the resulting local time reflects the timezone where the event was created,
     * not necessarily the family's timezone. If a member creates an event at 3 PM EST while
     * traveling, it will appear as 3 PM even for a PST-based family (correct would be 12 PM PST).
     * A future improvement would convert to a configurable family timezone instead.
     */
    private ZonedDateTime toZonedDateTime(DateTime googleDateTime) {
        return Instant.ofEpochMilli(googleDateTime.getValue())
                .atZone(ZoneOffset.ofTotalSeconds(googleDateTime.getTimeZoneShift() * 60));
    }
}
