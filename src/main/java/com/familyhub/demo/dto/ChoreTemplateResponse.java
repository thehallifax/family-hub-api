package com.familyhub.demo.dto;

import com.familyhub.demo.model.ChoreCadence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.util.UUID;

public record ChoreTemplateResponse(
        UUID id,
        String title,
        UUID assignedToMemberId,
        ChoreCadence cadence,
        LocalDate activeFrom,
        boolean archived,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        DayOfWeek dueWeekday,
        Integer dueDayOfMonth,
        LocalDate recurrenceAnchorDate
) {
    public ChoreTemplateResponse(UUID id, String title, UUID assignedToMemberId,
                                 ChoreCadence cadence, LocalDate activeFrom, boolean archived,
                                 LocalDateTime createdAt, LocalDateTime updatedAt,
                                 DayOfWeek dueWeekday, Integer dueDayOfMonth) {
        this(id, title, assignedToMemberId, cadence, activeFrom, archived,
                createdAt, updatedAt, dueWeekday, dueDayOfMonth, null);
    }
    public ChoreTemplateResponse(UUID id, String title, UUID assignedToMemberId,
                                 ChoreCadence cadence, LocalDate activeFrom, boolean archived,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, title, assignedToMemberId, cadence, activeFrom, archived,
                createdAt, updatedAt, null, null, null);
    }
}
