package com.familyhub.demo.controller;

import com.familyhub.demo.config.SecurityConfig;
import com.familyhub.demo.dto.GoogleSyncResult;
import com.familyhub.demo.security.JwtAuthenticationEntryPoint;
import com.familyhub.demo.security.JwtAuthenticationFilter;
import com.familyhub.demo.security.WithMockFamily;
import com.familyhub.demo.service.FamilyMemberService;
import com.familyhub.demo.service.FamilyService;
import com.familyhub.demo.service.GoogleCalendarSyncService;
import com.familyhub.demo.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.familyhub.demo.TestDataFactory.MEMBER_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GoogleSyncController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class})
@ActiveProfiles("test")
class GoogleSyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private FamilyService familyService;

    @MockitoBean
    private FamilyMemberService familyMemberService;

    @MockitoBean
    private GoogleCalendarSyncService syncService;

    @Test
    @WithMockFamily
    void syncMember_returnsCompletedResult() throws Exception {
        when(syncService.syncMemberNow(MEMBER_ID))
                .thenReturn(new GoogleSyncResult(1, java.util.List.of(), "Google calendars synced successfully."));
        mockMvc.perform(post("/api/google/sync/{memberId}", MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(1))
                .andExpect(jsonPath("$.data.failedCalendars").isEmpty());

        verify(familyMemberService).findById(any(), eq(MEMBER_ID));
        verify(syncService).syncMemberNow(MEMBER_ID);
    }

    @Test
    @WithMockFamily
    void syncMember_wrongFamily_returns403() throws Exception {
        doThrow(new AccessDeniedException("Unauthorized"))
                .when(familyMemberService).findById(any(), eq(MEMBER_ID));

        mockMvc.perform(post("/api/google/sync/{memberId}", MEMBER_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void syncMember_returns401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(post("/api/google/sync/{memberId}", MEMBER_ID))
                .andExpect(status().isUnauthorized());
    }
}
