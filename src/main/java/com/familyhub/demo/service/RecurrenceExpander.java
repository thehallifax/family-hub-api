package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.mapper.CalendarEventMapper;
import com.familyhub.demo.model.CalendarEvent;
import net.fortuna.ical4j.model.Recur;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class RecurrenceExpander {

    public List<CalendarEventResponse> expand(
            CalendarEvent parent,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            Map<LocalDate, CalendarEvent> exceptions
    ) {
        Recur<LocalDate> recur = new Recur<>(parent.getRecurrenceRule());
        long spanDays = parent.getEndDate() == null ? 0
                : Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(parent.getDate(), parent.getEndDate()));
        List<LocalDate> dates = recur.getDates(parent.getDate(), rangeStart.minusDays(spanDays), rangeEnd);

        // Filter out EXDATE dates (excluded by the recurrence rule source, e.g., Google Calendar)
        Set<LocalDate> excludedDates = parseExdates(parent.getExdates());

        List<CalendarEventResponse> results = new ArrayList<>();
        for (LocalDate date : dates) {
            if (excludedDates.contains(date)) {
                continue;
            }
            CalendarEvent exception = exceptions.get(date);
            if (exception != null) {
                if (exception.isCancelled()) {
                    continue; // Skip cancelled instances
                }
                // Use edited exception data
                CalendarEventResponse response = CalendarEventMapper.toDto(exception);
                if (overlaps(response, rangeStart, rangeEnd)) {
                    results.add(response);
                }
            } else {
                // Virtual instance from parent
                CalendarEventResponse response = CalendarEventMapper.toInstanceResponse(parent, date);
                if (overlaps(response, rangeStart, rangeEnd)) {
                    results.add(response);
                }
            }
        }
        return results;
    }

    private boolean overlaps(CalendarEventResponse event, LocalDate rangeStart, LocalDate rangeEnd) {
        LocalDate endDate = event.endDate() == null ? event.date() : event.endDate();
        return !event.date().isAfter(rangeEnd) && !endDate.isBefore(rangeStart);
    }

    private Set<LocalDate> parseExdates(String exdates) {
        if (exdates == null || exdates.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(exdates.split(","))
                .map(LocalDate::parse)
                .collect(Collectors.toSet());
    }
}
