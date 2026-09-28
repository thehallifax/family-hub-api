package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.dto.GoogleEventCreateRequest;
import com.familyhub.demo.dto.GoogleEventUpdateRequest;
import com.familyhub.demo.dto.GoogleWriteDestinations;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.GoogleEventCreationService;
import com.familyhub.demo.service.GoogleEventDeletionService;
import com.familyhub.demo.service.GoogleEventUpdateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/google/events")
@RequiredArgsConstructor
public class GoogleEventController {
    private final GoogleEventCreationService creation;
    private final GoogleEventDeletionService deletion;
    private final GoogleEventUpdateService update;

    @GetMapping("/destinations")
    public ResponseEntity<ApiResponse<GoogleWriteDestinations>> destinations(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(creation.destinations(family), null));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CalendarEventResponse>> create(@AuthenticationPrincipal Family family,
                                                                       @Valid @RequestBody GoogleEventCreateRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(creation.create(family, request), "Google event created"));
    }

    @DeleteMapping("/{eventId}")
    public ResponseEntity<Void> delete(@PathVariable UUID eventId,
                                       @AuthenticationPrincipal Family family) {
        deletion.delete(family, eventId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{eventId}")
    public ResponseEntity<ApiResponse<CalendarEventResponse>> update(
            @PathVariable UUID eventId,
            @AuthenticationPrincipal Family family,
            @Valid @RequestBody GoogleEventUpdateRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(update.update(family, eventId, request),
                "Google event updated"));
    }
}
