package com.familyhub.demo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import com.familyhub.demo.model.EventAudienceType;

public record CalendarEventRequest(
        @NotEmpty
        @Size(max = 100, message = "Event title must be 100 characters or less")
        String title,

        @NotEmpty
        @Pattern(regexp = "^(1[0-2]|[1-9]):[0-5][0-9] (AM|PM)$", message = "Time must be in 12-hour format (e.g., 2:00 PM)")
        String startTime,

        @NotEmpty
        @Pattern(regexp = "^(1[0-2]|[1-9]):[0-5][0-9] (AM|PM)$", message = "Time must be in 12-hour format (e.g., 2:00 PM)")
        String endTime,

        @NotNull
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate date,

        @NotNull
        EventAudienceType audienceType,

        @NotNull
        List<UUID> memberIds,

        // optional
        Boolean isAllDay,

        // optional
        @Size(max = 255)
        String location,

        // optional — only valid for all-day events
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate endDate,

        // optional — RRULE string (e.g., "FREQ=WEEKLY;BYDAY=TU,TH")
        String recurrenceRule,

        // optional — event description
        @Size(max = 2000, message = "Description must be 2000 characters or less")
        String description
) {
    /** Old Java test fixtures; HTTP clients must send the explicit audience fields. */
    public CalendarEventRequest(String title, String startTime, String endTime,
                                LocalDate date, UUID memberId, Boolean isAllDay,
                                String location, LocalDate endDate, String recurrenceRule,
                                String description) {
        this(title, startTime, endTime, date, EventAudienceType.MEMBERS,
                List.of(memberId), isAllDay, location, endDate, recurrenceRule, description);
    }
}
