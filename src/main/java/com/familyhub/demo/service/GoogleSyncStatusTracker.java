package com.familyhub.demo.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Recent operational state only. Last successful sync times remain stored per calendar. */
@Component
public class GoogleSyncStatusTracker {
    public record Status(Instant lastAttemptAt, String issue) {}

    private final ConcurrentHashMap<UUID, Status> statuses = new ConcurrentHashMap<>();

    public void record(UUID memberId, String issue) {
        statuses.put(memberId, new Status(Instant.now(), issue));
    }

    public Status get(UUID memberId) {
        return statuses.get(memberId);
    }

    public void clear(UUID memberId) {
        statuses.remove(memberId);
    }
}
