package com.familyhub.demo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record GoogleEventCreateRequest(
        @NotNull UUID sourceOwnerMemberId,
        @NotNull UUID syncedCalendarId,
        @NotNull UUID clientRequestId,
        @NotNull @Valid CalendarEventRequest event
) {}
