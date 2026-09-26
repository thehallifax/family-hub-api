package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.GoogleSyncResult;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.FamilyMemberService;
import com.familyhub.demo.service.GoogleCalendarSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// TODO: Add rate limiting or cooldown check (e.g., reject sync if lastSyncedAt < 60s ago)
//  to prevent excessive Google API calls and database churn from rapid-fire requests.
@RestController
@RequestMapping("/api/google/sync")
@RequiredArgsConstructor
public class GoogleSyncController {
    private final FamilyMemberService familyMemberService;
    private final GoogleCalendarSyncService syncService;

    @PostMapping("/{memberId}")
    public ResponseEntity<ApiResponse<GoogleSyncResult>> syncMember(
            @PathVariable UUID memberId,
            @AuthenticationPrincipal Family family) {
        familyMemberService.findById(family, memberId);

        GoogleSyncResult result = syncService.syncMemberNow(memberId);
        return ResponseEntity.ok(new ApiResponse<>(result, result.message()));
    }
}
