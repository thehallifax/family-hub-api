package com.familyhub.demo.controller;

import com.familyhub.demo.config.GoogleOAuthConfig;
import com.familyhub.demo.config.SecurityConfig;
import com.familyhub.demo.dto.GoogleConnectionStatus;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.security.JwtAuthenticationEntryPoint;
import com.familyhub.demo.security.JwtAuthenticationFilter;
import com.familyhub.demo.security.WithMockFamily;
import com.familyhub.demo.service.FamilyMemberService;
import com.familyhub.demo.service.FamilyService;
import com.familyhub.demo.service.GoogleOAuthService;
import com.familyhub.demo.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.familyhub.demo.TestDataFactory.MEMBER_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GoogleOAuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class})
@ActiveProfiles("test")
class GoogleOAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private FamilyService familyService;

    @MockitoBean
    private GoogleOAuthService googleOAuthService;

    @MockitoBean
    private FamilyMemberService familyMemberService;

    @MockitoBean
    private GoogleOAuthConfig googleOAuthConfig;

    @Test
    @WithMockFamily
    void getAuthorizationUrl_returnUrl() throws Exception {
        when(googleOAuthService.buildAuthorizationUrl(MEMBER_ID))
                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?client_id=test");

        mockMvc.perform(get("/api/google/auth").param("memberId", MEMBER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value(
                        "https://accounts.google.com/o/oauth2/v2/auth?client_id=test"));
    }

    @Test
    @WithMockFamily
    void getAuthorizationUrl_wrongFamily_returns403() throws Exception {
        doThrow(new AccessDeniedException("Unauthorized"))
                .when(familyMemberService).findById(any(), eq(MEMBER_ID));

        mockMvc.perform(get("/api/google/auth").param("memberId", MEMBER_ID.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockFamily
    void getAuthorizationUrl_unconfigured_returnsStructuredBadRequest() throws Exception {
        when(googleOAuthService.buildAuthorizationUrl(MEMBER_ID))
                .thenThrow(new BadRequestException("Google Calendar integration is not configured on this server"));

        mockMvc.perform(get("/api/google/auth").param("memberId", MEMBER_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("not configured")));
    }

    @Test
    @WithMockFamily
    void getStatus_connected_returnsTrueWithCalendars() throws Exception {
        when(googleOAuthService.getConnectionStatus(MEMBER_ID))
                .thenReturn(new GoogleConnectionStatus(true, true, List.of(), null, null, null));

        String response = mockMvc.perform(get("/api/google/status/{memberId}", MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connected").value(true))
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.calendars").isArray())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("clientSecret", "accessToken", "refreshToken", "encryptionKey");
    }

    @Test
    @WithMockFamily
    void getStatus_disconnected_returnsFalseWithEmptyCalendars() throws Exception {
        when(googleOAuthService.getConnectionStatus(MEMBER_ID))
                .thenReturn(new GoogleConnectionStatus(false, false, List.of(), null, null, null));

        mockMvc.perform(get("/api/google/status/{memberId}", MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connected").value(false))
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.calendars").isEmpty());
    }

    @Test
    @WithMockFamily
    void disconnect_removesToken() throws Exception {
        mockMvc.perform(delete("/api/google/disconnect/{memberId}", MEMBER_ID))
                .andExpect(status().isOk());

        verify(googleOAuthService).disconnect(MEMBER_ID);
    }

    @Test
    @WithMockFamily
    void getStatus_wrongFamily_returns403() throws Exception {
        doThrow(new AccessDeniedException("Unauthorized"))
                .when(familyMemberService).findById(any(), eq(MEMBER_ID));

        mockMvc.perform(get("/api/google/status/{memberId}", MEMBER_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void callback_validState_redirectsToFrontend() throws Exception {
        String stateToken = UUID.randomUUID().toString();
        when(googleOAuthService.consumeState(stateToken)).thenReturn(Optional.of(MEMBER_ID));
        when(googleOAuthConfig.getFrontendRedirectUrl())
                .thenReturn("http://localhost:5173/settings");

        mockMvc.perform(get("/api/google/callback")
                        .param("code", "test-auth-code")
                        .param("state", stateToken))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/settings?googleConnected=true"));

        verify(googleOAuthService).exchangeCodeForTokens(eq("test-auth-code"), eq(MEMBER_ID));
    }

    @Test
    void callback_existingGoogleConnectedParameter_isReplacedOnce() throws Exception {
        String stateToken = UUID.randomUUID().toString();
        when(googleOAuthService.consumeState(stateToken)).thenReturn(Optional.of(MEMBER_ID));
        when(googleOAuthConfig.getFrontendRedirectUrl())
                .thenReturn("http://localhost:5173/settings?googleConnected=true&googleConnected=true");

        mockMvc.perform(get("/api/google/callback")
                        .param("code", "test-auth-code").param("state", stateToken))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/settings?googleConnected=true"));
    }

    @Test
    void callback_invalidState_returns400() throws Exception {
        when(googleOAuthService.consumeState("bogus-state")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/google/callback")
                        .param("code", "test-auth-code")
                        .param("state", "bogus-state"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void callback_consentDenied_redirectsWithError() throws Exception {
        when(googleOAuthConfig.getFrontendRedirectUrl())
                .thenReturn("http://localhost:5173/settings?googleConnected=true");

        mockMvc.perform(get("/api/google/callback")
                        .param("error", "access_denied")
                        .param("state", "somestate"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/settings?error=consent_denied"));
    }

    @Test
    void callback_tokenExchangeFails_redirectsWithError() throws Exception {
        String stateToken = UUID.randomUUID().toString();
        when(googleOAuthService.consumeState(stateToken)).thenReturn(Optional.of(MEMBER_ID));
        when(googleOAuthService.exchangeCodeForTokens(eq("test-auth-code"), eq(MEMBER_ID)))
                .thenThrow(new RuntimeException("Google API error"));
        when(googleOAuthConfig.getFrontendRedirectUrl())
                .thenReturn("http://localhost:5173/settings");

        mockMvc.perform(get("/api/google/callback")
                        .param("code", "test-auth-code")
                        .param("state", stateToken))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:5173/settings?error=token_exchange_failed"));
    }
}
