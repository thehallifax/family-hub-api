package com.familyhub.demo.service;

import com.familyhub.demo.config.GoogleOAuthConfig;
import com.familyhub.demo.dto.GoogleTokenResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.repository.GoogleSyncedCalendarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.web.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleOAuthServiceTest {

    @Mock
    private GoogleOAuthTokenRepository tokenRepository;

    @Mock
    private FamilyMemberRepository memberRepository;

    @Mock
    private TokenEncryptionService encryptionService;

    @Mock
    private RestClient restClient;

    @Mock
    private OAuthStateStore stateStore;

    @Mock
    private GoogleSyncedCalendarRepository syncedCalendarRepository;

    @Mock
    private CalendarEventRepository calendarEventRepository;

    @Mock
    private GoogleSyncStatusTracker statusTracker;

    private GoogleOAuthConfig config;
    private GoogleOAuthService oauthService;

    @BeforeEach
    void setUp() {
        config = new GoogleOAuthConfig();
        config.setClientId("test-client-id");
        config.setClientSecret("test-client-secret");
        config.setRedirectUri("http://localhost:8080/api/google/callback");
        config.setFrontendRedirectUrl("http://localhost:8080");

        oauthService = new GoogleOAuthService(config, tokenRepository, memberRepository, encryptionService, stateStore,
                syncedCalendarRepository, calendarEventRepository, statusTracker, restClient);
    }

    @Test
    void buildAuthorizationUrl_containsRequiredParams() {
        UUID memberId = UUID.randomUUID();
        String stateToken = UUID.randomUUID().toString();
        when(stateStore.generateState(memberId)).thenReturn(stateToken);

        String url = oauthService.buildAuthorizationUrl(memberId);

        assertThat(url).contains("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(url).contains("client_id=test-client-id");
        assertThat(url).contains("redirect_uri=");
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("access_type=offline");
        assertThat(url).contains("prompt=consent");
        assertThat(url).contains("state=" + stateToken);
        assertThat(url).doesNotContain(memberId.toString());
        assertThat(url).contains("scope=");
        assertThat(url).contains("calendar.events.readonly");
        assertThat(url).contains("calendar.calendarlist.readonly");
    }

    @Test
    void buildAuthorizationUrl_unconfigured_rejectsBeforeGeneratingState() {
        config.setClientId(" ");
        assertThatThrownBy(() -> oauthService.buildAuthorizationUrl(UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not configured");
        verifyNoInteractions(stateStore);
    }

    @Test
    void connectionStatus_reportsCapabilityWithoutSecrets() {
        UUID memberId = UUID.randomUUID();
        assertThat(oauthService.getConnectionStatus(memberId).configured()).isTrue();
        config.setClientSecret("");
        assertThat(oauthService.getConnectionStatus(memberId).configured()).isFalse();
        config.setClientSecret("test-client-secret");
        config.setRedirectUri("not-a-url");
        assertThat(oauthService.getConnectionStatus(memberId).configured()).isFalse();
    }

    @Test
    void connectionStatus_usesLatestSuccessfulEnabledCalendar() {
        UUID memberId = UUID.randomUUID();
        when(tokenRepository.findByMemberId(memberId)).thenReturn(Optional.of(new GoogleOAuthToken()));
        GoogleSyncedCalendar old = new GoogleSyncedCalendar();
        old.setEnabled(true);
        old.setLastSyncedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        GoogleSyncedCalendar latest = new GoogleSyncedCalendar();
        latest.setEnabled(true);
        latest.setLastSyncedAt(java.time.Instant.parse("2026-01-03T00:00:00Z"));
        GoogleSyncedCalendar disabled = new GoogleSyncedCalendar();
        disabled.setEnabled(false);
        disabled.setLastSyncedAt(java.time.Instant.parse("2026-01-04T00:00:00Z"));
        when(syncedCalendarRepository.findByMemberId(memberId)).thenReturn(java.util.List.of(old, latest, disabled));

        assertThat(oauthService.getConnectionStatus(memberId).lastSuccessfulSyncAt())
                .isEqualTo(java.time.Instant.parse("2026-01-03T00:00:00Z"));
    }

    @Test
    void isConnected_withToken_returnsTrue() {
        UUID memberId = UUID.randomUUID();
        when(tokenRepository.findByMemberId(memberId))
                .thenReturn(Optional.of(new GoogleOAuthToken()));

        assertThat(oauthService.isConnected(memberId)).isTrue();
    }

    @Test
    void isConnected_withoutToken_returnsFalse() {
        UUID memberId = UUID.randomUUID();
        when(tokenRepository.findByMemberId(memberId))
                .thenReturn(Optional.empty());

        assertThat(oauthService.isConnected(memberId)).isFalse();
    }

    @Test
    void disconnect_revokesRefreshTokenAndCleansUp() {
        UUID memberId = UUID.randomUUID();
        FamilyMember member = new FamilyMember();
        member.setId(memberId);

        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setRefreshToken("encrypted-refresh");
        token.setMember(member);
        when(tokenRepository.findByMemberId(memberId))
                .thenReturn(Optional.of(token));
        when(encryptionService.decrypt("encrypted-refresh")).thenReturn("plain-refresh");

        oauthService.disconnect(memberId);

        verify(encryptionService).decrypt("encrypted-refresh");
        verify(syncedCalendarRepository).deleteByMemberId(memberId);
        verify(calendarEventRepository).deleteBySourceOwnerMemberAndSource(member, EventSource.GOOGLE);
        verify(tokenRepository).delete(token);
    }

    @Test
    void disconnect_noToken_doesNothing() {
        UUID memberId = UUID.randomUUID();
        when(tokenRepository.findByMemberId(memberId))
                .thenReturn(Optional.empty());

        oauthService.disconnect(memberId);

        verify(tokenRepository, never()).delete(any());
    }

    @Test
    void exchangeCodeForTokens_happyPath_savesToken() {
        UUID memberId = UUID.randomUUID();
        FamilyMember member = new FamilyMember();
        member.setId(memberId);

        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(tokenRepository.findByMemberId(memberId)).thenReturn(Optional.empty());
        when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "enc-" + inv.getArgument(0));
        when(tokenRepository.save(any(GoogleOAuthToken.class))).thenAnswer(inv -> inv.getArgument(0));

        GoogleTokenResponse googleResponse = new GoogleTokenResponse(
                "access-123", "refresh-456", 3600, "calendar.events.readonly");

        // Mock RestClient chain
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodyUriSpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodyUriSpec);
        when(bodyUriSpec.header(anyString(), anyString())).thenReturn(bodyUriSpec);
        when(bodyUriSpec.body(anyString())).thenReturn(bodyUriSpec);
        when(bodyUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(GoogleTokenResponse.class)).thenReturn(googleResponse);

        GoogleOAuthToken result = oauthService.exchangeCodeForTokens("auth-code", memberId);

        assertThat(result.getAccessToken()).isEqualTo("enc-access-123");
        assertThat(result.getRefreshToken()).isEqualTo("enc-refresh-456");
        verify(tokenRepository).save(any(GoogleOAuthToken.class));
    }

    @Test
    void reconnect_removesOnlyOwnersOldGoogleEventsBeforeTokenReplacement() {
        UUID memberId = UUID.randomUUID();
        FamilyMember member = new FamilyMember();
        member.setId(memberId);
        GoogleOAuthToken old = new GoogleOAuthToken();
        old.setMember(member);
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(tokenRepository.findByMemberId(memberId)).thenReturn(Optional.of(old));
        when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "enc-" + inv.getArgument(0));
        when(tokenRepository.save(any(GoogleOAuthToken.class))).thenAnswer(inv -> inv.getArgument(0));
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.body(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(GoogleTokenResponse.class))
                .thenReturn(new GoogleTokenResponse("new-access", "new-refresh", 3600, "read-only"));

        oauthService.exchangeCodeForTokens("code", memberId);

        var order = inOrder(calendarEventRepository, syncedCalendarRepository, tokenRepository);
        order.verify(syncedCalendarRepository).findByMemberIdForUpdate(memberId);
        order.verify(calendarEventRepository).deleteBySourceOwnerMemberAndSource(member, EventSource.GOOGLE);
        order.verify(syncedCalendarRepository).deleteByMemberId(memberId);
        order.verify(syncedCalendarRepository).flush();
        order.verify(tokenRepository).delete(old);
        order.verify(tokenRepository).flush();
        order.verify(tokenRepository).save(any(GoogleOAuthToken.class));
    }

    @Test
    void exchangeCodeForTokens_nullResponse_throwsBadRequest() {
        UUID memberId = UUID.randomUUID();
        FamilyMember member = new FamilyMember();
        member.setId(memberId);
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member));

        // Mock RestClient chain returning null
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodyUriSpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodyUriSpec);
        when(bodyUriSpec.header(anyString(), anyString())).thenReturn(bodyUriSpec);
        when(bodyUriSpec.body(anyString())).thenReturn(bodyUriSpec);
        when(bodyUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(GoogleTokenResponse.class)).thenReturn(null);

        assertThatThrownBy(() -> oauthService.exchangeCodeForTokens("bad-code", memberId))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void exchangeCodeForTokens_missingAccessToken_throwsBadRequest() {
        UUID memberId = UUID.randomUUID();
        FamilyMember member = new FamilyMember();
        member.setId(memberId);
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member));

        GoogleTokenResponse googleResponse = new GoogleTokenResponse(
                null, "refresh-456", 3600, "scope");

        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodyUriSpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodyUriSpec);
        when(bodyUriSpec.header(anyString(), anyString())).thenReturn(bodyUriSpec);
        when(bodyUriSpec.body(anyString())).thenReturn(bodyUriSpec);
        when(bodyUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(GoogleTokenResponse.class)).thenReturn(googleResponse);

        assertThatThrownBy(() -> oauthService.exchangeCodeForTokens("bad-code", memberId))
                .isInstanceOf(BadRequestException.class);
    }
}
