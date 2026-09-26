package com.familyhub.demo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;

import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import com.familyhub.demo.model.EventAudienceType;

@Builder
public record CalendarEventResponse(
        UUID id,
        String title,
        String startTime,
        String endTime,
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate date,
        UUID memberId,
        EventAudienceType audienceType,
        List<UUID> memberIds,
        boolean isAllDay,
        String location,
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate endDate,
        String recurrenceRule,
        UUID recurringEventId,
        boolean isRecurring,
        String source,
        String description,
        String htmlLink
) {

}
