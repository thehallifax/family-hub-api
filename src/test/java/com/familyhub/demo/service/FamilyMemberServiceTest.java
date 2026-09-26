package com.familyhub.demo.service;

import com.familyhub.demo.dto.FamilyMemberRequest;
import com.familyhub.demo.dto.FamilyMemberResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.EventAudienceType;
import com.familyhub.demo.repository.ChoreTemplateRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.CalendarEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.familyhub.demo.TestDataFactory.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FamilyMemberServiceTest {

    @Mock
    private FamilyMemberRepository familyMemberRepository;

    @Mock
    private ChoreTemplateRepository choreTemplateRepository;

    @Mock
    private CalendarEventRepository calendarEventRepository;

    @InjectMocks
    private FamilyMemberService familyMemberService;

    private Family family;
    private FamilyMember member;

    @BeforeEach
    void setUp() {
        family = createFamily();
        family.setFamilyMembers(List.of());
        member = createFamilyMember(family);
    }

    @Test
    void findAllMembers_returnsList() {
        when(familyMemberRepository.findByFamily(family)).thenReturn(List.of(member));

        List<FamilyMemberResponse> result = familyMemberService.findAllMembers(family);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("Test Member");
    }

    @Test
    void findById_found_returnsResponse() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));

        FamilyMemberResponse result = familyMemberService.findById(family, MEMBER_ID);

        assertThat(result.id()).isEqualTo(MEMBER_ID);
        assertThat(result.name()).isEqualTo("Test Member");
    }

    @Test
    void findById_notFound_throws() {
        UUID unknownId = UUID.randomUUID();
        when(familyMemberRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> familyMemberService.findById(family, unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findById_wrongFamily_throwsAccessDenied() {
        Family otherFamily = createOtherFamily();
        FamilyMember wrongMember = createFamilyMember(otherFamily);
        UUID wrongMemberId = UUID.randomUUID();
        wrongMember.setId(wrongMemberId);

        when(familyMemberRepository.findById(wrongMemberId)).thenReturn(Optional.of(wrongMember));

        assertThatThrownBy(() -> familyMemberService.findById(family, wrongMemberId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void addFamilyMember_success() {
        FamilyMemberRequest request = createFamilyMemberRequest();
        when(familyMemberRepository.save(any(FamilyMember.class))).thenReturn(member);

        FamilyMemberResponse result = familyMemberService.addFamilyMember(family, request);

        assertThat(result.id()).isEqualTo(MEMBER_ID);
        assertThat(result.name()).isEqualTo("Test Member");
        verify(familyMemberRepository).save(any(FamilyMember.class));
    }

    @Test
    void updateFamilyMember_success_verifySave() {
        FamilyMemberRequest request = createFamilyMemberRequest();
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(familyMemberRepository.save(any(FamilyMember.class))).thenReturn(member);

        FamilyMemberResponse result = familyMemberService.updateFamilyMember(family, MEMBER_ID, request);

        assertThat(result.id()).isEqualTo(MEMBER_ID);
        assertThat(result.name()).isEqualTo("Test Member");
        verify(familyMemberRepository).save(any(FamilyMember.class));
    }

    @Test
    void updateFamilyMember_wrongFamily_throwsAccessDenied() {
        Family otherFamily = createOtherFamily();
        FamilyMember wrongMember = createFamilyMember(otherFamily);
        UUID wrongMemberId = UUID.randomUUID();
        wrongMember.setId(wrongMemberId);

        FamilyMemberRequest request = createFamilyMemberRequest();
        when(familyMemberRepository.findById(wrongMemberId)).thenReturn(Optional.of(wrongMember));

        assertThatThrownBy(() -> familyMemberService.updateFamilyMember(family, wrongMemberId, request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteFamilyMember_success() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(choreTemplateRepository.existsByAssignedToMemberAndArchivedAtIsNull(member)).thenReturn(false);

        familyMemberService.deleteFamilyMember(family, MEMBER_ID);

        verify(familyMemberRepository).delete(member);
    }

    @Test
    void deletingMemberKeepsSharedEventForRemainingPerson() {
        FamilyMember another = createFamilyMember(family);
        another.setId(UUID.randomUUID());
        CalendarEvent shared = createCalendarEvent(family, member);
        shared.getAudienceMembers().add(another);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(calendarEventRepository.findDistinctByAudienceMembersContaining(member)).thenReturn(List.of(shared));

        familyMemberService.deleteFamilyMember(family, MEMBER_ID);

        assertThat(shared.getAudienceMembers()).containsExactly(another);
        verify(calendarEventRepository, org.mockito.Mockito.never()).delete(shared);
    }

    @Test
    void deletingSoleAudienceMemberDeletesEventRatherThanMakingItFamily() {
        CalendarEvent sole = createCalendarEvent(family, member);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(calendarEventRepository.findDistinctByAudienceMembersContaining(member)).thenReturn(List.of(sole));

        familyMemberService.deleteFamilyMember(family, MEMBER_ID);

        verify(calendarEventRepository).delete(sole);
        assertThat(sole.getAudienceType()).isEqualTo(EventAudienceType.MEMBERS);
    }

    @Test
    void deletingMemberDoesNotDeleteFamilyEvent() {
        CalendarEvent everyone = createCalendarEvent(family, member);
        everyone.setAudienceType(EventAudienceType.FAMILY);
        everyone.getAudienceMembers().clear();
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));

        familyMemberService.deleteFamilyMember(family, MEMBER_ID);

        verify(calendarEventRepository, org.mockito.Mockito.never()).delete(everyone);
        assertThat(everyone.getAudienceType()).isEqualTo(EventAudienceType.FAMILY);
    }

    @Test
    void deleteFamilyMember_withActiveRecurringChores_throwsBadRequest() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(choreTemplateRepository.existsByAssignedToMemberAndArchivedAtIsNull(member)).thenReturn(true);

        assertThatThrownBy(() -> familyMemberService.deleteFamilyMember(family, MEMBER_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Reassign or archive this member's recurring chores before deleting them.");
    }

    @Test
    void deleteFamilyMember_wrongFamily_throwsAccessDenied() {
        Family otherFamily = createOtherFamily();
        FamilyMember wrongMember = createFamilyMember(otherFamily);
        UUID wrongMemberId = UUID.randomUUID();
        wrongMember.setId(wrongMemberId);

        when(familyMemberRepository.findById(wrongMemberId)).thenReturn(Optional.of(wrongMember));

        assertThatThrownBy(() -> familyMemberService.deleteFamilyMember(family, wrongMemberId))
                .isInstanceOf(AccessDeniedException.class);
    }
}
