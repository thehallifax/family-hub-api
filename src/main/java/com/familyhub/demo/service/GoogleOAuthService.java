package com.familyhub.demo.service;

import com.familyhub.demo.config.GoogleOAuthConfig;
import com.familyhub.demo.dto.GoogleConnectionStatus;
import com.familyhub.demo.dto.GoogleTokenResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.repository.GoogleSyncedCalendarRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
public class GoogleOAuthService {
    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";
    private static final String SCOPES = "https://www.googleapis.com/auth/calendar.events.readonly " +
            "https://www.googleapis.com/auth/calendar.calendarlist.readonly";

    private final GoogleOAuthConfig config;
    private final GoogleOAuthTokenRepository tokenRepository;
    private final FamilyMemberRepository memberRepository;
    private final TokenEncryptionService encryptionService;
    private final OAuthStateStore stateStore;
    private final GoogleSyncedCalendarRepository syncedCalendarRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final RestClient restClient;

    @Autowired
    public GoogleOAuthService(GoogleOAuthConfig config,
                              GoogleOAuthTokenRepository tokenRepository,
                              FamilyMemberRepository memberRepository,
                              TokenEncryptionService encryptionService,
                              OAuthStateStore stateStore,
                              GoogleSyncedCalendarRepository syncedCalendarRepository,
                              CalendarEventRepository calendarEventRepository) {
        this(config, tokenRepository, memberRepository, encryptionService, stateStore,
                syncedCalendarRepository, calendarEventRepository, RestClient.create());
    }

    public GoogleOAuthService(GoogleOAuthConfig config,
                              GoogleOAuthTokenRepository tokenRepository,
                              FamilyMemberRepository memberRepository,
                              TokenEncryptionService encryptionService,
                              OAuthStateStore stateStore,
                              GoogleSyncedCalendarRepository syncedCalendarRepository,
                              CalendarEventRepository calendarEventRepository,
                              RestClient restClient) {
        this.config = config;
        this.tokenRepository = tokenRepository;
        this.memberRepository = memberRepository;
        this.encryptionService = encryptionService;
        this.stateStore = stateStore;
        this.syncedCalendarRepository = syncedCalendarRepository;
        this.calendarEventRepository = calendarEventRepository;
        this.restClient = restClient;
    }

    public String buildAuthorizationUrl(UUID memberId) {
        if (!config.isConfigured()) {
            throw new BadRequestException("Google Calendar integration is not configured on this server");
        }
        String state = stateStore.generateState(memberId);
        return AUTH_URL + "?" +
                "client_id=" + encode(config.getClientId()) +
                "&redirect_uri=" + encode(config.getRedirectUri()) +
                "&response_type=code" +
                "&scope=" + encode(SCOPES) +
                "&access_type=offline" +
                "&prompt=consent" +
                "&state=" + encode(state);
    }

    public Optional<UUID> consumeState(String state) {
        return stateStore.consumeState(state);
    }

    @Transactional
    public GoogleOAuthToken exchangeCodeForTokens(String code, UUID memberId) {
        FamilyMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family Member", memberId));

        GoogleTokenResponse response = restClient.post()
                .uri(TOKEN_URL)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("code=" + encode(code) +
                        "&client_id=" + encode(config.getClientId()) +
                        "&client_secret=" + encode(config.getClientSecret()) +
                        "&redirect_uri=" + encode(config.getRedirectUri()) +
                        "&grant_type=authorization_code")
                .retrieve()
                .body(GoogleTokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new BadRequestException("Failed to exchange authorization code for tokens");
        }

        if (response.refreshToken() == null) {
            throw new BadRequestException("Google did not return a refresh token");
        }

        // Delete existing token if reconnecting (flush to avoid unique constraint violation)
        tokenRepository.findByMemberId(memberId)
                .ifPresent(existing -> {
                    tokenRepository.delete(existing);
                    tokenRepository.flush();
                });

        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setMember(member);
        token.setAccessToken(encryptionService.encrypt(response.accessToken()));
        token.setRefreshToken(encryptionService.encrypt(response.refreshToken()));
        token.setTokenExpiry(Instant.now().plusSeconds(
                response.expiresIn() != null ? response.expiresIn() : 3600));
        token.setScope(response.scope() != null ? response.scope() : SCOPES);

        return tokenRepository.save(token);
    }

    @Transactional
    public void disconnect(UUID memberId) {
        tokenRepository.findByMemberId(memberId).ifPresent(token -> {
            // Best-effort revoke the refresh token at Google (more durable than access token)
            try {
                String refreshToken = encryptionService.decrypt(token.getRefreshToken());
                restClient.post()
                        .uri(REVOKE_URL + "?token=" + encode(refreshToken))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("Failed to revoke Google token for member {}: {}", memberId, e.getMessage());
            }

            // Delete synced calendar rows (CASCADE from token would handle this, but be explicit)
            syncedCalendarRepository.deleteByMemberId(memberId);

            // Delete all Google-sourced calendar events for this member
            calendarEventRepository.deleteByMemberAndSource(token.getMember(), EventSource.GOOGLE);

            tokenRepository.delete(token);
        });
    }

    public boolean isConnected(UUID memberId) {
        return tokenRepository.findByMemberId(memberId).isPresent();
    }

    public GoogleConnectionStatus getConnectionStatus(UUID memberId) {
        boolean connected = isConnected(memberId);

        List<GoogleConnectionStatus.SyncedCalendarInfo> calendars = connected
                ? syncedCalendarRepository.findByMemberId(memberId).stream()
                    .map(sc -> new GoogleConnectionStatus.SyncedCalendarInfo(
                            sc.getGoogleCalendarId(),
                            sc.getCalendarName(),
                            sc.isEnabled(),
                            sc.getLastSyncedAt()))
                    .toList()
                : List.of();

        return new GoogleConnectionStatus(config.isConfigured(), connected, calendars);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
