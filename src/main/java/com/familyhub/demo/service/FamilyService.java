package com.familyhub.demo.service;

import com.familyhub.demo.dto.FamilyRequest;
import com.familyhub.demo.dto.FamilyResponse;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.mapper.FamilyMapper;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.FamilyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FamilyService {
    private final FamilyRepository familyRepository;
    private final AppearanceMediaService appearanceMediaService;

    @Transactional
    public FamilyResponse findFamilyResponse(UUID familyId) {
        Family family = familyRepository.findById(familyId)
                .orElseThrow(() -> new ResourceNotFoundException("Family", familyId));
        return FamilyMapper.toDto(family);
    }

    public Family findFamilyById(UUID familyId) {
        return familyRepository.findById(familyId)
                .orElseThrow(() -> new ResourceNotFoundException("Family", familyId));
    }

    @Transactional
    public FamilyResponse updateFamily(UUID id, FamilyRequest family) {
        Family toBeUpdated = findFamilyById(id);

        if (family.name() != null) {
            toBeUpdated.setName(family.name());
        }

        if (family.username() != null) {
            toBeUpdated.setUsername(family.username());
        }

        if (family.timezone() != null) {
            toBeUpdated.setTimezone(FamilyTimezoneResolver.normalizeRequestedTimezone(family.timezone()));
        }

        return FamilyMapper.toDto(familyRepository.save(toBeUpdated));
    }

    @Transactional
    public void deleteFamily(UUID id) {
        Family toBeDeleted = findFamilyById(id);
        familyRepository.delete(toBeDeleted);
        familyRepository.flush();
        appearanceMediaService.deleteFamilyAfterCommit(id);
    }
}
