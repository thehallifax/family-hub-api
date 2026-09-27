package com.familyhub.demo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record GoogleEventUpdateRequest(@NotNull @Valid CalendarEventRequest event) {}
