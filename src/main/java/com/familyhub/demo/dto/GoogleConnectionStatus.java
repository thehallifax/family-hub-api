package com.familyhub.demo.dto;

import java.time.Instant;
import java.util.List;

public record GoogleConnectionStatus(
        boolean configured,
        boolean connected,
        List<SyncedCalendarInfo> calendars,
        Instant lastSuccessfulSyncAt,
        Instant lastAttemptAt,
        String syncIssue
) {
    public record SyncedCalendarInfo(
            String id,
            String name,
            boolean enabled,
            Instant lastSyncedAt
    ) {}
}
