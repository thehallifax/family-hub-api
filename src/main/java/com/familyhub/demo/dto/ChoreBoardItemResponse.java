package com.familyhub.demo.dto;

import com.familyhub.demo.model.ChoreCadence;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.UUID;

public record ChoreBoardItemResponse(
        UUID templateId,
        String title,
        ChoreCadence cadence,
        UUID assignedToMemberId,
        boolean completed,
        LocalDateTime completedAt,
        DayOfWeek dueWeekday,
        Integer dueDayOfMonth,
        LocalDate dueDate,
        ChoreDueState dueState
) {
    public ChoreBoardItemResponse(UUID templateId, String title, ChoreCadence cadence,
                                  UUID assignedToMemberId, boolean completed, LocalDateTime completedAt) {
        this(templateId, title, cadence, assignedToMemberId, completed, completedAt,
                null, null, null, completed ? ChoreDueState.COMPLETE : ChoreDueState.UNSCHEDULED);
    }
}
