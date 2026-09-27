package com.familyhub.demo.dto;

public record GoogleCalendarResponse(
        String id,
        String name,
        boolean primary,
        boolean enabled,
        String accessRole,
        boolean writable
) {
    public GoogleCalendarResponse(String id, String name, boolean primary, boolean enabled) {
        this(id, name, primary, enabled, null, false);
    }
}
