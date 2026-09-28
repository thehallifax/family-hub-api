package com.familyhub.demo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record GoogleEventUpdateRequest(
        @NotNull @Valid CalendarEventRequest event,
        GoogleEventEditScope scope,
        LocalDate occurrenceDate
) {}
