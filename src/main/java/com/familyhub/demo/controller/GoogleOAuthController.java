package com.familyhub.demo.controller;

import com.familyhub.demo.config.GoogleOAuthConfig;
import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.GoogleAuthUrlResponse;
import com.familyhub.demo.dto.GoogleConnectionStatus;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.FamilyMemberService;
import com.familyhub.demo.service.GoogleOAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/google")
@RequiredArgsConstructor
public class GoogleOAuthController {
    private final GoogleOAuthService googleOAuthService;
    private final GoogleOAuthConfig googleOAuthConfig;
    private final FamilyMemberService familyMemberService;

    @GetMapping("/auth")
    public ResponseEntity<ApiResponse<GoogleAuthUrlResponse>> getAuthorizationUrl(
            @RequestParam UUID memberId,
            @AuthenticationPrincipal Family family) {
        familyMemberService.findById(family, memberId);

        String url = googleOAuthService.buildAuthorizationUrl(memberId);
        return ResponseEntity.ok(new ApiResponse<>(
                new GoogleAuthUrlResponse(url),
                "Authorization URL generated"));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> handleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        if (error != null || code == null) {
            return ResponseEntity.status(302)
                    .location(frontendRedirect("error", "consent_denied"))
                    .build();
        }

        UUID memberId = googleOAuthService.consumeState(state)
                .orElseThrow(() -> new BadRequestException("Invalid or expired OAuth state"));

        try {
            googleOAuthService.exchangeCodeForTokens(code, memberId);
        } catch (Exception e) {
            log.error("Token exchange failed for member {}: {}", memberId, e.getMessage());
            return ResponseEntity.status(302)
                    .location(frontendRedirect("error", "token_exchange_failed"))
                    .build();
        }

        return ResponseEntity.status(302)
                .location(frontendRedirect("googleConnected", "true"))
                .build();
    }

    private URI frontendRedirect(String parameter, String value) {
        return UriComponentsBuilder.fromUriString(googleOAuthConfig.getFrontendRedirectUrl())
                .replaceQueryParam("googleConnected")
                .replaceQueryParam("error")
                .queryParam(parameter, value)
                .build()
                .toUri();
    }

    @DeleteMapping("/disconnect/{memberId}")
    public ResponseEntity<ApiResponse<Void>> disconnect(
            @PathVariable UUID memberId,
            @AuthenticationPrincipal Family family) {
        familyMemberService.findById(family, memberId);

        googleOAuthService.disconnect(memberId);
        return ResponseEntity.ok(new ApiResponse<>(null, "Google account disconnected"));
    }

    @GetMapping("/status/{memberId}")
    public ResponseEntity<ApiResponse<GoogleConnectionStatus>> getConnectionStatus(
            @PathVariable UUID memberId,
            @AuthenticationPrincipal Family family) {
        familyMemberService.findById(family, memberId);

        GoogleConnectionStatus status = googleOAuthService.getConnectionStatus(memberId);
        return ResponseEntity.ok(new ApiResponse<>(status, null));
    }
}
