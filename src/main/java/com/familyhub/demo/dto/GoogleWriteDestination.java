package com.familyhub.demo.dto;

import java.util.UUID;

public record GoogleWriteDestination(UUID syncedCalendarId, UUID memberId, String memberName,
                                     String calendarName, String accessRole) {}
