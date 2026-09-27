package com.familyhub.demo.dto;

public record GoogleCalendarInfo(
        String id,
        String name,
        boolean primary,
        String accessRole
) {
    public GoogleCalendarInfo(String id, String name, boolean primary) {
        this(id, name, primary, null);
    }

    public boolean writable() {
        return "writer".equals(accessRole) || "owner".equals(accessRole)
                || "writerWithoutPrivateAccess".equals(accessRole);
    }
}
