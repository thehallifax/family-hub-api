package com.familyhub.demo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.familyhub.demo.model.ChoreCadence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.UUID;

public record CreateChoreTemplateRequest(
        @NotBlank(message = "Chore title is required")
        @Size(max = 100, message = "Chore title must be 100 characters or less")
        String title,

        @NotNull
        UUID assignedToMemberId,

        @NotNull
        ChoreCadence cadence,

        @NotNull
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate activeFrom,

        DayOfWeek dueWeekday,

        Integer dueDayOfMonth
) {
    public CreateChoreTemplateRequest(String title, UUID assignedToMemberId,
                                      ChoreCadence cadence, LocalDate activeFrom) {
        this(title, assignedToMemberId, cadence, activeFrom, null, null);
    }
}
