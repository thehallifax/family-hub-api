package com.familyhub.demo.service;


import com.familyhub.demo.dto.FamilyMemberRequest;
import com.familyhub.demo.dto.FamilyMemberResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.mapper.FamilyMemberMapper;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.repository.ChoreTemplateRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.model.EventAudienceType;
import com.familyhub.demo.model.EventSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FamilyMemberService {
    private final FamilyMemberRepository familyMemberRepository;
    private final ChoreTemplateRepository choreTemplateRepository;
    private final CalendarEventRepository calendarEventRepository;

    public List<FamilyMemberResponse> findAllMembers(Family family) {
         return familyMemberRepository.findByFamily(family)
                 .stream()
                 .map(FamilyMemberMapper::toDto)
                 .toList();
    }

    public FamilyMemberResponse findById(Family family, UUID familyMemberId) {
        FamilyMember familyMember = findById(familyMemberId);
        if (!isMemberOfFamily(family, familyMember)) {
            throw new AccessDeniedException("Unauthorized");
        }
        return FamilyMemberMapper.toDto(familyMember);
    }

    @Transactional
    public FamilyMemberResponse addFamilyMember(Family family, FamilyMemberRequest toAdd) {
        FamilyMember saved = familyMemberRepository.save(FamilyMemberMapper.toEntity(toAdd, family));
        log.info("Family member created, memberId={}, familyId={}", saved.getId(), family.getId());
        return FamilyMemberMapper.toDto(saved);
    }

    @Transactional
    public FamilyMemberResponse updateFamilyMember(
            Family family,
            UUID familyMemberId,
            FamilyMemberRequest update) {

        // Check if familyMember with passed uuid exists
        FamilyMember toBeUpdated = findById(familyMemberId);

        // Check if the uuid passed as args belongs to authenticated family
        if (!isMemberOfFamily(family, toBeUpdated)) {
            throw new AccessDeniedException("Unauthorized");
        }

        // Apply updates
        toBeUpdated.setName(update.name());
        toBeUpdated.setColor(update.color());
        toBeUpdated.setAvatarUrl(update.avatarUrl());
        toBeUpdated.setEmail(update.email());

        // Save
        FamilyMember saved = familyMemberRepository.save(toBeUpdated);
        log.info("Family member updated, memberId={}, familyId={}", saved.getId(), family.getId());
        return FamilyMemberMapper.toDto(saved);
    }

    @Transactional
    public void deleteFamilyMember(Family family, UUID familyMemberId) {
        // Check if familyMember with passed uuid exists
        FamilyMember toBeDeleted = findById(familyMemberId);

        // Check if the uuid passed as args belongs to authenticated family
        if (!isMemberOfFamily(family, toBeDeleted)) {
            throw new AccessDeniedException("Unauthorized");
        }

        if (choreTemplateRepository.existsByAssignedToMemberAndArchivedAtIsNull(toBeDeleted)) {
            throw new BadRequestException("Reassign or archive this member's recurring chores before deleting them.");
        }

        // Existing sole-member events disappeared with the old member_id cascade.
        // Keep shared events, but never leave a MEMBERS event with no audience.
        calendarEventRepository.deleteBySourceOwnerMemberAndSource(toBeDeleted, EventSource.GOOGLE);
        for (var event : calendarEventRepository.findDistinctByAudienceMembersContaining(toBeDeleted)) {
            if (event.getAudienceType() == EventAudienceType.MEMBERS) {
                if (event.getAudienceMembers().size() == 1) {
                    calendarEventRepository.delete(event);
                } else {
                    event.getAudienceMembers().remove(toBeDeleted);
                }
            }
        }
        calendarEventRepository.flush();
        familyMemberRepository.delete(toBeDeleted);
        log.info("Family member deleted, memberId={}, familyId={}", familyMemberId, family.getId());
    }

    // -- Helper methods --

    private FamilyMember findById(UUID familyMemberId) {
        return familyMemberRepository.findById(familyMemberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family Member", familyMemberId));
    }

    private boolean isMemberOfFamily(Family family, FamilyMember familyMember) {
        return familyMember.getFamily().getId()
                .equals(family.getId());
    }
}
