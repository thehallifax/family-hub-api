package com.familyhub.demo.mapper;

import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.EventAudienceType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.List;

public class CalendarEventMapper {
    private CalendarEventMapper() {}
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US);



    public static CalendarEvent toEntity(CalendarEventRequest request, Family family, List<FamilyMember> members) {
        CalendarEvent calendarEvent = new CalendarEvent();

        calendarEvent.setTitle(request.title());
        calendarEvent.setStartTime(parseTime(request.startTime()));
        calendarEvent.setEndTime(parseTime(request.endTime()));
        calendarEvent.setDate(request.date());
        calendarEvent.setAudienceType(request.audienceType());
        calendarEvent.getAudienceMembers().addAll(members);
        calendarEvent.setFamily(family);
        calendarEvent.setAllDay(request.isAllDay() != null && request.isAllDay());
        calendarEvent.setLocation(request.location());
        calendarEvent.setEndDate(request.endDate());
        calendarEvent.setRecurrenceRule(request.recurrenceRule());
        calendarEvent.setDescription(request.description());
        calendarEvent.setSource(EventSource.NATIVE);

        return  calendarEvent;
    }

    public static CalendarEvent toEntity(CalendarEventRequest request, Family family, FamilyMember member) {
        return toEntity(request, family, List.of(member));
    }

    public static CalendarEventResponse toDto(CalendarEvent calendarEvent) {
        return CalendarEventResponse.builder()
                .id(calendarEvent.getId())
                .title(calendarEvent.getTitle())
                .startTime(timeToString(calendarEvent.getStartTime()))
                .endTime(timeToString(calendarEvent.getEndTime()))
                .date(calendarEvent.getDate())
                .memberId(singleMemberId(calendarEvent))
                .audienceType(calendarEvent.getAudienceType())
                .memberIds(memberIds(calendarEvent))
                .isAllDay(calendarEvent.isAllDay())
                .location(calendarEvent.getLocation())
                .endDate(calendarEvent.getEndDate())
                .recurrenceRule(calendarEvent.getRecurrenceRule())
                .recurringEventId(calendarEvent.getRecurringEvent() != null
                        ? calendarEvent.getRecurringEvent().getId() : null)
                .isRecurring(calendarEvent.getRecurrenceRule() != null
                        || calendarEvent.getRecurringEvent() != null)
                .source(calendarEvent.getSource().name())
                .description(calendarEvent.getDescription())
                .htmlLink(calendarEvent.getHtmlLink())
                .build();
    }

    public static CalendarEventResponse toInstanceResponse(CalendarEvent parent, LocalDate instanceDate) {
        return CalendarEventResponse.builder()
                .id(null)
                .title(parent.getTitle())
                .startTime(timeToString(parent.getStartTime()))
                .endTime(timeToString(parent.getEndTime()))
                .date(instanceDate)
                .memberId(singleMemberId(parent))
                .audienceType(parent.getAudienceType())
                .memberIds(memberIds(parent))
                .isAllDay(parent.isAllDay())
                .location(parent.getLocation())
                .endDate(parent.getEndDate() == null ? null : instanceDate.plusDays(
                        java.time.temporal.ChronoUnit.DAYS.between(parent.getDate(), parent.getEndDate())))
                .recurrenceRule(parent.getRecurrenceRule())
                .recurringEventId(parent.getId())
                .isRecurring(true)
                .source(parent.getSource().name())
                .description(parent.getDescription())
                .htmlLink(parent.getHtmlLink())
                .build();
    }

    private static List<java.util.UUID> memberIds(CalendarEvent event) {
        return event.getAudienceMembers().stream()
                .map(FamilyMember::getId).sorted().toList();
    }

    private static java.util.UUID singleMemberId(CalendarEvent event) {
        List<java.util.UUID> ids = memberIds(event);
        return ids.size() == 1 ? ids.getFirst() : null;
    }

    private static String timeToString(LocalTime localTime) {
        return localTime.format(timeFormatter);
    }

    public static LocalTime parseTime(String time) {
        try {
            return LocalTime.parse(time, timeFormatter);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Error Parsing: " + time);
        }
    }
}
