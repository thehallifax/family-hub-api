package com.familyhub.demo.dto;

import java.util.List;

public record GoogleSyncResult(int succeeded, List<String> failedCalendars, String message) {
}
